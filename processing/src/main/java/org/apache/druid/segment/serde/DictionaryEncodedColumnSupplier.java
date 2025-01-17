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

package org.apache.druid.segment.serde;

import com.google.common.base.Supplier;
import org.apache.druid.common.config.NullHandling;
import org.apache.druid.segment.column.DictionaryEncodedColumn;
import org.apache.druid.segment.column.StringDictionaryEncodedColumn;
import org.apache.druid.segment.data.CachingIndexed;
import org.apache.druid.segment.data.ColumnarInts;
import org.apache.druid.segment.data.ColumnarMultiInts;
import org.apache.druid.segment.data.GenericIndexed;
import org.apache.druid.segment.data.Indexed;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;

/**
 *
 */
public class DictionaryEncodedColumnSupplier implements Supplier<DictionaryEncodedColumn<?>>
{
  private final GenericIndexed<String> dictionary;
  private final GenericIndexed<ByteBuffer> dictionaryUtf8;
  private final @Nullable Supplier<ColumnarInts> singleValuedColumn;
  private final @Nullable Supplier<ColumnarMultiInts> multiValuedColumn;
  private final int lookupCacheSize;

  public DictionaryEncodedColumnSupplier(
      GenericIndexed<String> dictionary,
      GenericIndexed<ByteBuffer> dictionaryUtf8,
      @Nullable Supplier<ColumnarInts> singleValuedColumn,
      @Nullable Supplier<ColumnarMultiInts> multiValuedColumn,
      int lookupCacheSize
  )
  {
    this.dictionary = dictionary;
    this.dictionaryUtf8 = dictionaryUtf8;
    this.singleValuedColumn = singleValuedColumn;
    this.multiValuedColumn = multiValuedColumn;
    this.lookupCacheSize = lookupCacheSize;
  }

  @Override
  public DictionaryEncodedColumn<?> get()
  {
    final Indexed<String> cacheWrappedDictionary;
    final Indexed<ByteBuffer> singleThreadedDictionaryUtf8 = dictionaryUtf8.singleThreaded();

    if (lookupCacheSize > 0) {
      cacheWrappedDictionary = new CachingIndexed<>(
          dictionary.singleThreaded(),
          s -> s == null ? 0 : s.length() * Character.BYTES,
          lookupCacheSize
      );
    } else {
      cacheWrappedDictionary = dictionary.singleThreaded();
    }

    if (NullHandling.mustCombineNullAndEmptyInDictionary(singleThreadedDictionaryUtf8)) {
      return new StringDictionaryEncodedColumn(
          singleValuedColumn != null ? new CombineFirstTwoValuesColumnarInts(singleValuedColumn.get()) : null,
          multiValuedColumn != null ? new CombineFirstTwoValuesColumnarMultiInts(multiValuedColumn.get()) : null,
          CombineFirstTwoEntriesIndexed.returnNull(cacheWrappedDictionary),
          CombineFirstTwoEntriesIndexed.returnNull(singleThreadedDictionaryUtf8)
      );
    } else if (NullHandling.mustReplaceFirstValueWithNullInDictionary(singleThreadedDictionaryUtf8)) {
      return new StringDictionaryEncodedColumn(
          singleValuedColumn != null ? singleValuedColumn.get() : null,
          multiValuedColumn != null ? multiValuedColumn.get() : null,
          new ReplaceFirstValueWithNullIndexed<>(cacheWrappedDictionary),
          new ReplaceFirstValueWithNullIndexed<>(singleThreadedDictionaryUtf8)
      );
    } else {
      return new StringDictionaryEncodedColumn(
          singleValuedColumn != null ? singleValuedColumn.get() : null,
          multiValuedColumn != null ? multiValuedColumn.get() : null,
          cacheWrappedDictionary,
          singleThreadedDictionaryUtf8
      );
    }
  }
}
