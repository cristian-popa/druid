/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.segment.data;

import org.apache.druid.java.util.common.Pair;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.query.monomorphicprocessing.RuntimeShapeInspector;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToIntFunction;

public class CachingIndexed<T> implements CloseableIndexed<T>
{
  private static final int INITIAL_CACHE_CAPACITY = 16384;

  private static final Logger log = new Logger(CachingIndexed.class);

  private final Indexed<T> delegate;
  private final ToIntFunction<T> sizeFn;
  @Nullable
  private final SizedLRUMap<Integer, T> cachedValues;

  /**
   * Creates a CachingIndexed wrapping the given GenericIndexed with a value lookup cache
   *
   * CachingIndexed objects are not thread safe and should only be used by a single thread at a time.
   * CachingIndexed objects must be closed to release any underlying cache resources.
   *
   * @param delegate the Indexed to wrap with a lookup cache.
   * @param sizeFn function that determines the size in bytes of an object
   * @param lookupCacheSize maximum size in bytes of the lookup cache if greater than zero
   */
  public CachingIndexed(Indexed<T> delegate, final ToIntFunction<T> sizeFn, final int lookupCacheSize)
  {
    this.delegate = delegate;
    this.sizeFn = sizeFn;

    if (lookupCacheSize > 0) {
      log.debug("Allocating column cache of max size[%d]", lookupCacheSize);
      cachedValues = new SizedLRUMap<>(INITIAL_CACHE_CAPACITY, lookupCacheSize);
    } else {
      cachedValues = null;
    }
  }

  @Override
  public int size()
  {
    return delegate.size();
  }

  @Override
  public T get(int index)
  {
    if (cachedValues != null) {
      final T cached = cachedValues.getValue(index);
      if (cached != null) {
        return cached;
      }

      final T value = delegate.get(index);
      cachedValues.put(index, value, sizeFn.applyAsInt(value));
      return value;
    } else {
      return delegate.get(index);
    }
  }

  @Override
  public int indexOf(@Nullable T value)
  {
    return delegate.indexOf(value);
  }

  @Override
  public boolean isSorted()
  {
    return delegate.isSorted();
  }

  @Override
  public Iterator<T> iterator()
  {
    return delegate.iterator();
  }

  @Override
  public void close()
  {
    if (cachedValues != null) {
      log.debug("Closing column cache");
      cachedValues.clear();
    }
  }

  @Override
  public void inspectRuntimeShape(RuntimeShapeInspector inspector)
  {
    inspector.visit("cachedValues", cachedValues != null);
    inspector.visit("delegate", delegate);
  }

  private static class SizedLRUMap<K, V> extends LinkedHashMap<K, Pair<Integer, V>>
  {
    private final int maxBytes;
    private int numBytes = 0;

    SizedLRUMap(int initialCapacity, int maxBytes)
    {
      super(initialCapacity, 0.75f, true);
      this.maxBytes = maxBytes;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, Pair<Integer, V>> eldest)
    {
      if (numBytes > maxBytes) {
        numBytes -= eldest.getValue().lhs;
        return true;
      }
      return false;
    }

    public void put(K key, @Nullable V value, int size)
    {
      final int totalSize = size + 48; // add approximate object overhead
      numBytes += totalSize;
      super.put(key, new Pair<>(totalSize, value));
    }

    @Nullable
    public V getValue(Object key)
    {
      final Pair<Integer, V> sizeValuePair = super.get(key);
      return sizeValuePair == null ? null : sizeValuePair.rhs;
    }
  }
}
