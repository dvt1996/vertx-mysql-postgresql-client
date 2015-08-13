package io.vertx.ext.asyncsql.impl;

import com.github.mauricio.async.db.Connection;
import com.github.mauricio.async.db.QueryResult;
import com.github.mauricio.async.db.RowData;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.asyncsql.impl.pool.AsyncConnectionPool;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.sql.UpdateResult;
import org.joda.time.LocalDate;
import org.joda.time.LocalDateTime;
import scala.Option;
import scala.concurrent.ExecutionContext;
import scala.runtime.AbstractFunction1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AsyncSQLConnectionImpl implements SQLConnection {

  private final ExecutionContext executionContext;
  private boolean inTransaction = false;
  private boolean inAutoCommit = true;

  private final Connection connection;
  private final AsyncConnectionPool pool;

  public AsyncSQLConnectionImpl(Connection connection, AsyncConnectionPool pool, ExecutionContext executionContext) {
    this.connection = connection;
    this.pool = pool;
    this.executionContext = executionContext;
  }

  @Override
  public SQLConnection setAutoCommit(boolean autoCommit, Handler<AsyncResult<Void>> handler) {
    Future<Void> fut;

    if (inTransaction && autoCommit) {
      inTransaction = false;
      fut = ScalaUtils.scalaToVertxVoid(connection.sendQuery("COMMIT"), executionContext);
    } else {
      fut = Future.succeededFuture();
    }

    inAutoCommit = autoCommit;
    fut.setHandler(handler);

    return this;
  }

  @Override
  public SQLConnection execute(String sql, Handler<AsyncResult<Void>> handler) {
    beginTransactionIfNeeded().setHandler(v -> {
      final scala.concurrent.Future<QueryResult> future = connection.sendQuery(sql);
      future.onComplete(ScalaUtils.<QueryResult>toFunction1(ar -> {
        if (ar.failed()) {
          handler.handle(Future.failedFuture(ar.cause()));
        } else {
          handler.handle(Future.succeededFuture());
        }
      }), executionContext);
    });

    return this;
  }

  @Override
  public SQLConnection query(String sql, Handler<AsyncResult<ResultSet>> handler) {
    beginTransactionIfNeeded().setHandler(v -> {
      final scala.concurrent.Future<QueryResult> future = connection.sendQuery(sql);
      future.onComplete(ScalaUtils.<QueryResult>toFunction1(ar -> {
        if (ar.succeeded()) {
          handler.handle(Future.succeededFuture(queryResultToResultSet(ar.result())));
        } else {
          handler.handle(Future.failedFuture(ar.cause()));
        }

      }),executionContext);
    });

    return this;
  }

  @Override
  public SQLConnection queryWithParams(String sql, JsonArray params, Handler<AsyncResult<ResultSet>> handler) {
    beginTransactionIfNeeded().setHandler(v -> {
      final scala.concurrent.Future<QueryResult> future = connection.sendPreparedStatement(sql,
          ScalaUtils.toScalaList(params.getList()));
      future.onComplete(ScalaUtils.<QueryResult>toFunction1(ar -> {
        if (ar.succeeded()) {
          handler.handle(Future.succeededFuture(queryResultToResultSet(ar.result())));
        } else {
          handler.handle(Future.failedFuture(ar.cause()));
        }

      }),executionContext);
    });

    return this;
  }

  @Override
  public SQLConnection update(String sql, Handler<AsyncResult<UpdateResult>> handler) {
    beginTransactionIfNeeded().setHandler(v -> {
      final scala.concurrent.Future<QueryResult> future = connection.sendQuery(sql);
      future.onComplete(ScalaUtils.<QueryResult>toFunction1(ar -> {
        if (ar.failed()) {
          handler.handle(Future.failedFuture(ar.cause()));
        } else {
          handler.handle(Future.succeededFuture(queryResultToUpdateResult(ar.result())));
        }
      }), executionContext);
    });

    return this;
  }

  @Override
  public SQLConnection updateWithParams(String sql, JsonArray params, Handler<AsyncResult<UpdateResult>> handler) {
    beginTransactionIfNeeded().setHandler(v -> {
      final scala.concurrent.Future<QueryResult> future = connection.sendPreparedStatement(sql,
          ScalaUtils.toScalaList(params.getList()));
      future.onComplete(ScalaUtils.<QueryResult>toFunction1(ar -> {
        if (ar.failed()) {
          handler.handle(Future.failedFuture(ar.cause()));
        } else {
          handler.handle(Future.succeededFuture(queryResultToUpdateResult(ar.result())));
        }
      }), executionContext);
    });

    return this;
  }

  @Override
  public void close(Handler<AsyncResult<Void>> handler) {
    inAutoCommit = true;
    if (inTransaction) {
      inTransaction = false;
      Future<QueryResult> future = ScalaUtils.scalaToVertx(connection.sendQuery("COMMIT"), executionContext);
      future.setHandler((v) -> {
        pool.giveBack(connection);
        handler.handle(Future.succeededFuture());
      });
    } else {
      pool.giveBack(connection);
      handler.handle(Future.succeededFuture());
    }
  }

  @Override
  public void close() {
    close((ar) -> {

    });
  }

  @Override
  public SQLConnection commit(Handler<AsyncResult<Void>> handler) {
    return endAndStartTransaction("COMMIT", handler);
  }

  @Override
  public SQLConnection rollback(Handler<AsyncResult<Void>> handler) {
    return endAndStartTransaction("ROLLBACK", handler);
  }

  private SQLConnection endAndStartTransaction(String command, Handler<AsyncResult<Void>> handler) {
    if (inTransaction) {
      inTransaction = false;
      ScalaUtils.scalaToVertx(connection.sendQuery(command), executionContext).setHandler(
          ar -> {
            if (ar.failed()) {
              handler.handle(Future.failedFuture(ar.cause()));
            } else {
              ScalaUtils.scalaToVertx(connection.sendQuery("BEGIN"), executionContext).setHandler(
                  ar2 -> {
                    if (ar2.failed()) {
                      handler.handle(Future.failedFuture(ar.cause()));
                    } else {
                      inTransaction = true;
                      handler.handle(Future.succeededFuture());
                    }
                  }
              );
            }
          });
    } else {
      handler.handle(Future.failedFuture(new IllegalStateException("Not in transaction currently")));
    }
    return this;
  }

  private Future<Void> beginTransactionIfNeeded() {
    if (!inAutoCommit && !inTransaction) {
      inTransaction = true;
      return ScalaUtils.scalaToVertxVoid(connection.sendQuery("BEGIN"), executionContext);
    } else {
      return Future.succeededFuture();
    }
  }

  private ResultSet queryResultToResultSet(QueryResult qr) {
    final Option<com.github.mauricio.async.db.ResultSet> rows = qr.rows();
    if (rows.isDefined()) {
      return new ResultSet(Collections.emptyList(), Collections.emptyList());
    } else {
      final List<String> names = ScalaUtils.toJavaList(rows.get().columnNames().toList());
      final List<JsonArray> arrays = rowDataSeqToJsonArray(rows.get());
      return new ResultSet(names, arrays);
    }
  }

  private UpdateResult queryResultToUpdateResult(QueryResult qr) {
    int affected = (int) qr.rowsAffected();
    final Option<com.github.mauricio.async.db.ResultSet> maybeRow = qr.rows();
    if (maybeRow.isDefined()) {
      return new UpdateResult(affected, new JsonArray(ScalaUtils.toJavaList(maybeRow.get().columnNames().toList())));
    } else {
      return new UpdateResult(affected, new JsonArray());
    }
  }

  private List<JsonArray> rowDataSeqToJsonArray(com.github.mauricio.async.db.ResultSet set) {
    List<JsonArray> list = new ArrayList<>();
    set.foreach(new AbstractFunction1<RowData, Void>() {
      @Override
      public Void apply(RowData row) {
        list.add(rowToJsonArray(row));
        return null;
      }
    });
    return list;
  }

  private JsonArray rowToJsonArray(RowData data) {
    JsonArray array = new JsonArray();
    data.foreach(new AbstractFunction1<Object, Void>() {
      @Override
      public Void apply(Object value) {
        if (value == null) {
          array.addNull();
        } else if (value instanceof LocalDateTime) {
          array.add(value.toString());
        } else if (value instanceof LocalDate) {
          array.add(value.toString());
        } else {
          array.add(value);
        }
        return null;
      }
    });
    return array;
  }
}
