/*
 * Copyright 2014 Red Hat, Inc.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.core.shareddata.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.impl.Arguments;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.shareddata.AsyncMap;
import io.vertx.core.shareddata.Counter;
import io.vertx.core.shareddata.LocalMap;
import io.vertx.core.shareddata.Lock;
import io.vertx.core.shareddata.SharedData;
import io.vertx.core.spi.cluster.ClusterManager;

import java.io.Serializable;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class SharedDataImpl implements SharedData {

  private static final long DEFAULT_LOCK_TIMEOUT = 10 * 1000;

  private final VertxInternal vertx;
  private final ClusterManager clusterManager;
  private final ConcurrentMap<String, AsynchronousLock> localLocks = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Counter> localCounters = new ConcurrentHashMap<>();
  private final ConcurrentMap<Object, LocalMap<?, ?>> localMaps = new ConcurrentHashMap<>();

  public SharedDataImpl(VertxInternal vertx, ClusterManager clusterManager) {
    this.vertx = vertx;
    this.clusterManager = clusterManager;
  }

  @Override
  public <K, V> void getClusterWideMap(String name, Handler<AsyncResult<AsyncMap<K, V>>> resultHandler) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(resultHandler, "resultHandler");
    if (clusterManager == null) {
      throw new IllegalStateException("Can't get cluster wide map if not clustered");
    }
    clusterManager.<K, V>getAsyncMap(name, null, ar -> {
      if (ar.succeeded()) {
        // Wrap it
        resultHandler.handle(Future.completedFuture(new WrappedAsyncMap<K, V>(ar.result())));
      } else {
        resultHandler.handle(Future.completedFuture(ar.cause()));
      }
    });
  }

  @Override
  public void getLock(String name, Handler<AsyncResult<Lock>> resultHandler) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(resultHandler, "resultHandler");
    getLockWithTimeout(name, DEFAULT_LOCK_TIMEOUT, resultHandler);
  }

  @Override
  public void getLockWithTimeout(String name, long timeout, Handler<AsyncResult<Lock>> resultHandler) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(resultHandler, "resultHandler");
    Arguments.require(timeout >= 0, "timeout must be >= 0");
    if (clusterManager == null) {
      getLocalLock(name, timeout, resultHandler);
    } else {
      clusterManager.getLockWithTimeout(name, timeout, resultHandler);
    }
  }

  @Override
  public void getCounter(String name, Handler<AsyncResult<Counter>> resultHandler) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(resultHandler, "resultHandler");
    if (clusterManager == null) {
      getLocalCounter(name, resultHandler);
    } else {
      clusterManager.getCounter(name, resultHandler);
    }
  }

  /**
   * Return a {@code Map} with the specific {@code name}. All invocations of this method with the same value of {@code name}
   * are guaranteed to return the same {@code Map} instance. <p>
   */
  @SuppressWarnings("unchecked")
  public <K, V> LocalMap<K, V> getLocalMap(String name) {
    LocalMap<K, V> map = (LocalMap<K, V>) localMaps.get(name);
    if (map == null) {
      map = new LocalMapImpl<>(name, localMaps);
      LocalMap prev = localMaps.putIfAbsent(name, map);
      if (prev != null) {
        map = prev;
      }
    }
    return map;
  }


  private void getLocalLock(String name, long timeout, Handler<AsyncResult<Lock>> resultHandler) {
    AsynchronousLock lock = new AsynchronousLock(vertx);
    AsynchronousLock prev = localLocks.putIfAbsent(name, lock);
    if (prev != null) {
      lock = prev;
    }
    lock.acquire(timeout, resultHandler);
  }

  private void getLocalCounter(String name, Handler<AsyncResult<Counter>> resultHandler) {
    Counter counter = new AsynchronousCounter(vertx);
    Counter prev = localCounters.putIfAbsent(name, counter);
    if (prev != null) {
      counter = prev;
    }
    Counter theCounter = counter;
    Context context = vertx.getOrCreateContext();
    context.runOnContext(v -> resultHandler.handle(Future.completedFuture(theCounter)));
  }

  private static void checkType(Object obj) {
    if (obj == null) {
      throw new IllegalArgumentException("Cannot put null in key or value of cluster wide map");
    }
    Class<?> clazz = obj.getClass();
    if (clazz == Integer.class || clazz == int.class ||
      clazz == Long.class || clazz == long.class ||
      clazz == Short.class || clazz == short.class ||
      clazz == Float.class || clazz == float.class ||
      clazz == Double.class || clazz == double.class ||
      clazz == Boolean.class || clazz == boolean.class ||
      clazz == Byte.class || clazz == byte.class ||
      clazz == String.class || clazz == byte[].class) {
      // Basic types - can go in as is
      return;
    } else if (obj instanceof ClusterSerializable) {
      // OK
      return;
    } else if (obj instanceof Serializable) {
      // OK
      return;
    } else {
      throw new IllegalArgumentException("Invalid type: " + clazz + " to put in cluster wide map");
    }
  }

  private static class WrappedAsyncMap<K, V> implements AsyncMap<K, V> {

    private final AsyncMap<K, V> delegate;

    WrappedAsyncMap(AsyncMap<K, V> other) {
      this.delegate = other;
    }

    @Override
    public void get(K k, Handler<AsyncResult<V>> asyncResultHandler) {
      delegate.get(k, asyncResultHandler);
    }

    @Override
    public void put(K k, V v, Handler<AsyncResult<Void>> completionHandler) {
      checkType(k);
      checkType(v);
      delegate.put(k, v, completionHandler);
    }

    @Override
    public void putIfAbsent(K k, V v, Handler<AsyncResult<V>> completionHandler) {
      checkType(k);
      checkType(v);
      delegate.putIfAbsent(k, v, completionHandler);
    }

    @Override
    public void remove(K k, Handler<AsyncResult<V>> resultHandler) {
      delegate.remove(k, resultHandler);
    }

    @Override
    public void removeIfPresent(K k, V v, Handler<AsyncResult<Boolean>> resultHandler) {
      delegate.removeIfPresent(k, v, resultHandler);
    }

    @Override
    public void replace(K k, V v, Handler<AsyncResult<V>> resultHandler) {
      delegate.replace(k, v, resultHandler);
    }

    @Override
    public void replaceIfPresent(K k, V oldValue, V newValue, Handler<AsyncResult<Boolean>> resultHandler) {
      delegate.replaceIfPresent(k, oldValue, newValue, resultHandler);
    }

    @Override
    public void clear(Handler<AsyncResult<Void>> resultHandler) {
      delegate.clear(resultHandler);
    }
  }


}
