/*
 *
 *  *  Copyright 2010-2016 OrientDB LTD (http://orientdb.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://orientdb.com
 *
 */

package com.orientechnologies.orient.core.storage.index.sbtree.multivalue.v2;

import com.orientechnologies.common.comparator.ODefaultComparator;
import com.orientechnologies.common.exception.OException;
import com.orientechnologies.common.serialization.types.OBinarySerializer;
import com.orientechnologies.common.serialization.types.OByteSerializer;
import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.common.types.OModifiableLong;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.encryption.OEncryption;
import com.orientechnologies.orient.core.exception.NotEmptyComponentCanNotBeRemovedException;
import com.orientechnologies.orient.core.exception.OTooBigIndexKeyException;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.index.OAlwaysGreaterKey;
import com.orientechnologies.orient.core.index.OAlwaysLessKey;
import com.orientechnologies.orient.core.index.OCompositeKey;
import com.orientechnologies.orient.core.index.engine.OBaseIndexEngine;
import com.orientechnologies.orient.core.iterator.OEmptyIterator;
import com.orientechnologies.orient.core.iterator.OEmptyMapEntryIterator;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.storage.cache.OCacheEntry;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;
import com.orientechnologies.orient.core.storage.impl.local.paginated.atomicoperations.OAtomicOperation;
import com.orientechnologies.orient.core.storage.impl.local.paginated.atomicoperations.OAtomicOperationsManager;
import com.orientechnologies.orient.core.storage.impl.local.paginated.base.ODurableComponent;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.co.cellbtreemultivalue.OCellBTreeMultiValuePutCO;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.co.cellbtreemultivalue.OCellBtreeMultiValueRemoveEntryCO;
import com.orientechnologies.orient.core.storage.index.sbtree.local.OSBTree;
import com.orientechnologies.orient.core.storage.index.sbtree.local.v2.OSBTreeV2;
import com.orientechnologies.orient.core.storage.index.sbtree.multivalue.OCellBTreeMultiValue;

import java.io.IOException;
import java.util.*;

/**
 * This is implementation which is based on B+-tree implementation threaded tree. The main differences are:
 * <ol>
 * <li>Buckets are not compacted/removed if they are empty after deletion of item. They reused later when new items are added.</li>
 * <li>All non-leaf buckets have links to neighbor buckets which contain keys which are less/more than keys contained in current
 * bucket</li> <ol/> There is support of null values for keys, but values itself cannot be null. Null keys support is switched off
 * by default if null keys are supported value which is related to null key will be stored in separate file which has only one page.
 * Buckets/pages for usual (non-null) key-value entries can be considered as sorted array. The first bytes of page contains such
 * auxiliary information as size of entries contained in bucket, links to neighbors which contain entries with keys less/more than
 * keys in current bucket. The next bytes contain sorted array of entries. Array itself is split on two parts. First part is growing
 * from start to end, and second part is growing from end to start. First part is array of offsets to real key-value entries which
 * are stored in second part of array which grows from end to start. This array of offsets is sorted by accessing order according to
 * key value. So we can use binary search to find requested key. When new key-value pair is added we append binary presentation of
 * this pair to the second part of array which grows from end of page to start, remember value of offset for this pair, and find
 * proper position of this offset inside of first part of array. Such approach allows to minimize amount of memory involved in
 * performing of operations and as result speed up data processing.
 *
 * @author Andrey Lomakin (a.lomakin-at-orientdb.com)
 * @since 8/7/13
 */
public final class CellBTreeMultiValueV2<K> extends ODurableComponent implements OCellBTreeMultiValue<K> {
  private static final int               M_ID_BATCH_SIZE    = 131_072;
  private static final int               MAX_KEY_SIZE       = OGlobalConfiguration.SBTREE_MAX_KEY_SIZE.getValueAsInteger();
  private static final OAlwaysLessKey    ALWAYS_LESS_KEY    = new OAlwaysLessKey();
  private static final OAlwaysGreaterKey ALWAYS_GREATER_KEY = new OAlwaysGreaterKey();

  private static final int MAX_PATH_LENGTH = OGlobalConfiguration.SBTREE_MAX_DEPTH.getValueAsInteger();

  private static final int  ENTRY_POINT_INDEX = 0;
  private static final long ROOT_INDEX        = 1;

  private final Comparator<? super K> comparator = ODefaultComparator.INSTANCE;
  private final String                nullFileExtension;
  private final String                containerExtension;

  private long fileId;
  private long nullBucketFileId;

  private int                  keySize;
  private OBinarySerializer<K> keySerializer;
  private OType[]              keyTypes;
  private OEncryption          encryption;

  private       OSBTreeV2<MultiValueEntry, Byte> multiContainer;
  private final OModifiableLong                  mIdCounter = new OModifiableLong();
  private final int                              indexId;

  public CellBTreeMultiValueV2(final String name, int indexId, final String dataFileExtension, final String nullFileExtension,
      final String containerExtension, final OAbstractPaginatedStorage storage) {
    super(storage, name, dataFileExtension, name + dataFileExtension);
    this.indexId = indexId;
    acquireExclusiveLock();
    try {
      this.nullFileExtension = nullFileExtension;
      this.containerExtension = containerExtension;
    } finally {
      releaseExclusiveLock();
    }
  }

  public void create(final OBinarySerializer<K> keySerializer, final OType[] keyTypes, final int keySize,
      final OEncryption encryption) throws IOException {
    assert keySerializer != null;

    boolean rollback = false;
    final OAtomicOperation atomicOperation = startAtomicOperation(false);
    try {
      acquireExclusiveLock();
      try {

        this.keySize = keySize;
        if (keyTypes != null) {
          this.keyTypes = Arrays.copyOf(keyTypes, keyTypes.length);
        } else {
          this.keyTypes = null;
        }

        this.encryption = encryption;
        this.keySerializer = keySerializer;

        fileId = addFile(atomicOperation, getFullName());

        nullBucketFileId = addFile(atomicOperation, getName() + nullFileExtension);

        final OCacheEntry entryPointCacheEntry = addPage(atomicOperation, fileId);
        try {
          final CellBTreeMultiValueV2EntryPoint<K> entryPoint = new CellBTreeMultiValueV2EntryPoint<>(entryPointCacheEntry);
          entryPoint.init();
        } finally {
          releasePageFromWrite(atomicOperation, entryPointCacheEntry);
        }

        final OCacheEntry rootCacheEntry = addPage(atomicOperation, fileId);
        try {
          @SuppressWarnings("unused")
          final CellBTreeMultiValueV2Bucket<K> rootBucket = new CellBTreeMultiValueV2Bucket<>(rootCacheEntry);
          rootBucket.init(true);
        } finally {
          releasePageFromWrite(atomicOperation, rootCacheEntry);
        }

        final OCacheEntry nullBucketEntry = addPage(atomicOperation, nullBucketFileId);
        try {
          final CellBTreeMultiValueV2NullBucket nullBucket = new CellBTreeMultiValueV2NullBucket(nullBucketEntry);
          nullBucket.init(incrementMId(atomicOperation));
        } finally {
          releasePageFromWrite(atomicOperation, nullBucketEntry);
        }

        multiContainer = new OSBTreeV2<>(-1, getName(), containerExtension, null, storage);
        multiContainer.create(MultiValueEntrySerializer.INSTANCE, OByteSerializer.INSTANCE, null, 1, false, null);
      } finally {
        releaseExclusiveLock();
      }
    } catch (final Exception e) {
      rollback = true;
      throw e;
    } finally {
      endAtomicOperation(rollback);
    }

  }

  public List<ORID> get(K key) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();
        if (key != null) {
          //noinspection RedundantCast
          key = keySerializer.preprocess(key, (Object[]) keyTypes);

          final BucketSearchResult bucketSearchResult = findBucket(key, atomicOperation);
          if (bucketSearchResult.itemIndex < 0) {
            return Collections.emptyList();
          }

          final long pageIndex = bucketSearchResult.pageIndex;
          final int itemIndex = bucketSearchResult.itemIndex;

          long leftSibling = -1;
          long rightSibling = -1;

          final List<ORID> result = new ArrayList<>(8);
          OCacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex, false);
          try {
            final CellBTreeMultiValueV2Bucket<K> bucket = new CellBTreeMultiValueV2Bucket<>(cacheEntry);
            fetchValues(itemIndex, result, bucket);

            if (itemIndex == 0) {
              leftSibling = bucket.getLeftSibling();
            }

            if (itemIndex == bucket.size() - 1) {
              rightSibling = bucket.getRightSibling();
            }
          } finally {
            releasePageFromRead(atomicOperation, cacheEntry);
          }

          while (leftSibling >= 0) {
            cacheEntry = loadPageForRead(atomicOperation, fileId, leftSibling, false);
            try {
              final CellBTreeMultiValueV2Bucket<K> bucket = new CellBTreeMultiValueV2Bucket<>(cacheEntry);
              final int size = bucket.size();

              if (size > 0) {
                if (bucket.getKey(size - 1, keySerializer, encryption).equals(key)) {
                  fetchValues(size - 1, result, bucket);

                  if (size == 1) {
                    leftSibling = bucket.getLeftSibling();
                  } else {
                    leftSibling = -1;
                  }
                } else {
                  leftSibling = -1;
                }
              } else {
                leftSibling = bucket.getLeftSibling();
              }
            } finally {
              releasePageFromRead(atomicOperation, cacheEntry);
            }
          }

          while (rightSibling >= 0) {
            cacheEntry = loadPageForRead(atomicOperation, fileId, rightSibling, false);
            try {
              final CellBTreeMultiValueV2Bucket<K> bucket = new CellBTreeMultiValueV2Bucket<>(cacheEntry);
              final int size = bucket.size();

              if (size > 0) {
                if (bucket.getKey(0, keySerializer, encryption).equals(key)) {
                  fetchValues(0, result, bucket);

                  if (size == 1) {
                    rightSibling = bucket.getRightSibling();
                  } else {
                    rightSibling = -1;
                  }
                } else {
                  rightSibling = -1;
                }
              } else {
                rightSibling = bucket.getRightSibling();
              }
            } finally {
              releasePageFromRead(atomicOperation, cacheEntry);
            }
          }

          return result;
        } else {
          final OCacheEntry nullCacheEntry = loadPageForRead(atomicOperation, nullBucketFileId, 0, false);
          try {
            final CellBTreeMultiValueV2NullBucket nullBucket = new CellBTreeMultiValueV2NullBucket(nullCacheEntry);
            final int size = nullBucket.getSize();
            final List<ORID> values = nullBucket.getValues();
            if (values.size() < size) {
              final long mId = nullBucket.getMid();

              final OSBTree.OSBTreeCursor<MultiValueEntry, Byte> cursor = multiContainer
                  .iterateEntriesBetween(new MultiValueEntry(mId, 0, 0), true,
                      new MultiValueEntry(mId, Integer.MAX_VALUE, Long.MAX_VALUE), true, true);
              Map.Entry<MultiValueEntry, Byte> entry = cursor.next(-1);

              while (entry != null) {
                final MultiValueEntry multiValueEntry = entry.getKey();
                values.add(new ORecordId(multiValueEntry.clusterId, multiValueEntry.clusterPosition));

                entry = cursor.next(-1);
              }
            }
            return values;
          } finally {
            releasePageFromRead(atomicOperation, nullCacheEntry);
          }
        }
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException e) {
      throw OException
          .wrapException(new CellBTreeMultiValueException("Error during retrieving  of sbtree with name " + getName(), this), e);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  private void fetchValues(int itemIndex, List<ORID> result, CellBTreeMultiValueV2Bucket<K> bucket) {
    final CellBTreeMultiValueV2Bucket.LeafEntry entry = bucket.getLeafEntry(itemIndex, keySerializer, encryption != null);
    result.addAll(entry.values);
    if (entry.values.size() < entry.entriesCount) {
      final OSBTree.OSBTreeCursor<MultiValueEntry, Byte> cursor = multiContainer
          .iterateEntriesBetween(new MultiValueEntry(entry.mId, 0, 0), true,
              new MultiValueEntry(entry.mId, Integer.MAX_VALUE, Long.MAX_VALUE), true, true);

      Map.Entry<MultiValueEntry, Byte> cursorEntry = cursor.next(-1);
      while (cursorEntry != null) {
        final MultiValueEntry multiValueEntry = cursorEntry.getKey();
        result.add(new ORecordId(multiValueEntry.clusterId, multiValueEntry.clusterPosition));
        cursorEntry = cursor.next(-1);
      }
    }
  }

  private void fetchMapEntries(@SuppressWarnings("SameParameterValue") final int itemIndex, final K key,
      final List<Map.Entry<K, ORID>> result, final CellBTreeMultiValueV2Bucket<K> bucket) {
    final CellBTreeMultiValueV2Bucket.LeafEntry entry = bucket.getLeafEntry(itemIndex, keySerializer, encryption != null);
    fetchMapEntriesFromLeafEntry(key, result, entry);
  }

  private void fetchMapEntriesFromLeafEntry(K key, List<Map.Entry<K, ORID>> result, CellBTreeMultiValueV2Bucket.LeafEntry entry) {
    for (final ORID rid : entry.values) {
      result.add(new Map.Entry<K, ORID>() {
        @Override
        public K getKey() {
          return key;
        }

        @Override
        public ORID getValue() {
          return rid;
        }

        @Override
        public ORID setValue(final ORID value) {
          throw new UnsupportedOperationException("setValue");
        }
      });
    }

    if (entry.values.size() < entry.entriesCount) {
      final OSBTree.OSBTreeCursor<MultiValueEntry, Byte> cursor = multiContainer
          .iterateEntriesBetween(new MultiValueEntry(entry.mId, 0, 0), true,
              new MultiValueEntry(entry.mId, Integer.MAX_VALUE, Long.MAX_VALUE), true, true);

      Map.Entry<MultiValueEntry, Byte> cursorEntry = cursor.next(-1);
      final MultiValueEntry multiValueEntry = cursorEntry.getKey();

      while (cursorEntry != null) {
        result.add(new Map.Entry<K, ORID>() {
          @Override
          public K getKey() {
            return key;
          }

          @Override
          public ORID getValue() {
            return new ORecordId(multiValueEntry.clusterId, multiValueEntry.clusterPosition);
          }

          @Override
          public ORID setValue(final ORID value) {
            throw new UnsupportedOperationException("setValue");
          }
        });

        cursorEntry = cursor.next(-1);
      }
    }
  }

  public void put(K key, final ORID value) throws IOException {
    boolean rollback = false;
    final OAtomicOperation atomicOperation = startAtomicOperation(true);
    try {
      acquireExclusiveLock();
      try {
        if (key != null) {

          //noinspection RedundantCast
          key = keySerializer.preprocess(key, (Object[]) keyTypes);
          @SuppressWarnings("RedundantCast")
          final byte[] serializedKey = keySerializer.serializeNativeAsWhole(key, (Object[]) keyTypes);

          if (keySize > MAX_KEY_SIZE) {
            throw new OTooBigIndexKeyException(
                "Key size is more than allowed, operation was canceled. Current key size " + keySize + ", allowed  " + MAX_KEY_SIZE,
                getName());
          }

          UpdateBucketSearchResult bucketSearchResult = findBucketForUpdate(key, atomicOperation);

          OCacheEntry keyBucketCacheEntry = loadPageForWrite(atomicOperation, fileId, bucketSearchResult.getLastPathItem(), false,
              true);
          CellBTreeMultiValueV2Bucket<K> keyBucket = new CellBTreeMultiValueV2Bucket<>(keyBucketCacheEntry);

          final byte[] keyToInsert;
          keyToInsert = serializeKey(serializedKey);

          final boolean isNew;
          int insertionIndex;
          if (bucketSearchResult.itemIndex >= 0) {
            insertionIndex = bucketSearchResult.itemIndex;
            isNew = false;
          } else {
            insertionIndex = -bucketSearchResult.itemIndex - 1;
            isNew = true;
          }

          while (!addEntry(keyBucket, insertionIndex, isNew, keyToInsert, value, atomicOperation)) {
            bucketSearchResult = splitBucket(keyBucket, keyBucketCacheEntry, bucketSearchResult.path,
                bucketSearchResult.insertionIndexes, insertionIndex, key, atomicOperation);

            insertionIndex = bucketSearchResult.itemIndex;

            final long pageIndex = bucketSearchResult.getLastPathItem();

            if (pageIndex != keyBucketCacheEntry.getPageIndex()) {
              releasePageFromWrite(atomicOperation, keyBucketCacheEntry);

              keyBucketCacheEntry = loadPageForWrite(atomicOperation, fileId, pageIndex, false, true);
            }

            keyBucket = new CellBTreeMultiValueV2Bucket<>(keyBucketCacheEntry);
          }

          releasePageFromWrite(atomicOperation, keyBucketCacheEntry);

          updateSize(1, atomicOperation);

          atomicOperation.addComponentOperation(
              new OCellBTreeMultiValuePutCO((encryption != null ? encryption.name() : null), keySerializer.getId(), indexId,
                  keyToInsert, value));
        } else {
          final OCacheEntry nullCacheEntry = loadPageForWrite(atomicOperation, nullBucketFileId, 0, false, true);

          try {
            final CellBTreeMultiValueV2NullBucket nullBucket = new CellBTreeMultiValueV2NullBucket(nullCacheEntry);
            final long result = nullBucket.addValue(value);
            if (result >= 0) {
              multiContainer.validatedPut(new MultiValueEntry(result, value.getClusterId(), value.getClusterPosition()), (byte) 1,
                  (k, ov, v) -> {
                    if (ov != null) {
                      return OBaseIndexEngine.Validator.IGNORE;
                    }

                    nullBucket.incrementSize();
                    return v;
                  });
            }
          } finally {
            releasePageFromWrite(atomicOperation, nullCacheEntry);
          }

          updateSize(1, atomicOperation);

          atomicOperation.addComponentOperation(
              new OCellBTreeMultiValuePutCO((encryption != null ? encryption.name() : null), keySerializer.getId(), indexId, null,
                  value));
        }
      } finally {
        releaseExclusiveLock();
      }
    } catch (final RuntimeException | IOException e) {
      rollback = true;
      throw e;
    } finally {
      endAtomicOperation(rollback);
    }
  }

  private boolean addEntry(final CellBTreeMultiValueV2Bucket<K> bucketMultiValue, final int index, final boolean isNew,
      final byte[] key, final ORID value, final OAtomicOperation atomicOperation) throws IOException {

    if (isNew) {
      final long mId = incrementMId(atomicOperation);
      return bucketMultiValue.createMainLeafEntry(index, key, value, mId);
    }

    final long result = bucketMultiValue.appendNewLeafEntry(index, value);
    if (result >= 0) {
      multiContainer
          .validatedPut(new MultiValueEntry(result, value.getClusterId(), value.getClusterPosition()), (byte) 1, (k, ov, v) -> {
            if (ov != null) {
              return OBaseIndexEngine.Validator.IGNORE;
            }

            bucketMultiValue.incrementEntriesCount(index);
            return v;
          });
      return true;
    }

    return result == -1;
  }

  private long incrementMId(final OAtomicOperation atomicOperation) throws IOException {
    final long idCounter = mIdCounter.getValue();

    if ((idCounter & (M_ID_BATCH_SIZE - 1)) == 0) {
      final OCacheEntry cacheEntryPoint = loadPageForWrite(atomicOperation, fileId, ENTRY_POINT_INDEX, false, true);
      try {
        final CellBTreeMultiValueV2EntryPoint<K> entryPoint = new CellBTreeMultiValueV2EntryPoint<>(cacheEntryPoint);
        entryPoint.setEntryId(idCounter + M_ID_BATCH_SIZE);
      } finally {
        releasePageFromWrite(atomicOperation, cacheEntryPoint);
      }
    }

    mIdCounter.setValue(idCounter + 1);

    return idCounter + 1;
  }

  public void close() {
    acquireExclusiveLock();
    try {
      readCache.closeFile(fileId, true, writeCache);
      readCache.closeFile(nullBucketFileId, true, writeCache);
      multiContainer.close();
    } finally {
      releaseExclusiveLock();
    }
  }

  public void delete() throws IOException {
    boolean rollback = false;
    final OAtomicOperation atomicOperation = startAtomicOperation(false);
    try {
      acquireExclusiveLock();
      try {
        final long size = size();
        if (size > 0) {
          throw new NotEmptyComponentCanNotBeRemovedException(
              getName() + " : Not empty index can not be deleted. Index has " + size + " records");
        }
        deleteFile(atomicOperation, fileId);
        deleteFile(atomicOperation, nullBucketFileId);

        multiContainer.delete();
      } finally {
        releaseExclusiveLock();
      }
    } catch (final Exception e) {
      rollback = true;
      throw e;
    } finally {
      endAtomicOperation(rollback);
    }
  }

  public void load(final String name, final int keySize, final OType[] keyTypes, final OBinarySerializer<K> keySerializer,
      final OEncryption encryption) {
    acquireExclusiveLock();
    try {
      final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();

      fileId = openFile(atomicOperation, getFullName());
      nullBucketFileId = openFile(atomicOperation, name + nullFileExtension);

      this.keySize = keySize;
      this.keyTypes = keyTypes;
      this.encryption = encryption;
      this.keySerializer = keySerializer;

      multiContainer = new OSBTreeV2<>(-1, getName(), containerExtension, null, storage);
      multiContainer.load(getName(), MultiValueEntrySerializer.INSTANCE, OByteSerializer.INSTANCE, null, 1, false, null);

      final OCacheEntry entryPointCacheEntry = loadPageForRead(atomicOperation, fileId, ENTRY_POINT_INDEX, false);
      try {
        final CellBTreeMultiValueV2EntryPoint<K> entryPoint = new CellBTreeMultiValueV2EntryPoint<>(entryPointCacheEntry);
        mIdCounter.setValue(entryPoint.getEntryId());
      } finally {
        releasePageFromRead(atomicOperation, entryPointCacheEntry);
      }

    } catch (final IOException e) {
      throw OException.wrapException(new CellBTreeMultiValueException("Exception during loading of sbtree " + name, this), e);
    } finally {
      releaseExclusiveLock();
    }
  }

  public long size() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();

        final OCacheEntry entryPointCacheEntry = loadPageForRead(atomicOperation, fileId, ENTRY_POINT_INDEX, false);
        try {
          final CellBTreeMultiValueV2EntryPoint<K> entryPoint = new CellBTreeMultiValueV2EntryPoint<>(entryPointCacheEntry);
          return entryPoint.getTreeSize();
        } finally {
          releasePageFromRead(atomicOperation, entryPointCacheEntry);
        }
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException e) {
      throw OException
          .wrapException(new CellBTreeMultiValueException("Error during retrieving of size of index " + getName(), this), e);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public boolean remove(K key, final ORID value) throws IOException {
    boolean rollback = false;
    final OAtomicOperation atomicOperation = startAtomicOperation(true);

    try {
      boolean removed;
      acquireExclusiveLock();
      try {
        if (key != null) {
          //noinspection RedundantCast
          key = keySerializer.preprocess(key, (Object[]) keyTypes);

          final int serializeKeySize = keySerializer.getObjectSize(key) + (encryption == null ? 0 : OIntegerSerializer.INT_SIZE);

          final BucketSearchResult bucketSearchResult = findBucket(key, atomicOperation);
          if (bucketSearchResult.itemIndex < 0) {
            return false;
          }

          final long pageIndex = bucketSearchResult.pageIndex;
          final int itemIndex = bucketSearchResult.itemIndex;

          long leftSibling = -1;
          long rightSibling = -1;

          OCacheEntry cacheEntry = loadPageForWrite(atomicOperation, fileId, pageIndex, false, true);
          try {
            final CellBTreeMultiValueV2Bucket<K> bucket = new CellBTreeMultiValueV2Bucket<>(cacheEntry);

            removed = removeEntry(itemIndex, serializeKeySize, value, bucket);
            if (!removed) {
              if (itemIndex == 0) {
                leftSibling = bucket.getLeftSibling();
              }

              if (itemIndex == bucket.size() - 1) {
                rightSibling = bucket.getRightSibling();
              }
            }
          } finally {
            releasePageFromWrite(atomicOperation, cacheEntry);
          }

          while (!removed && leftSibling >= 0) {
            cacheEntry = loadPageForWrite(atomicOperation, fileId, leftSibling, false, true);
            try {
              final CellBTreeMultiValueV2Bucket<K> bucket = new CellBTreeMultiValueV2Bucket<>(cacheEntry);
              final int size = bucket.size();

              if (size > 0) {
                removed = removeEntry(size - 1, keySize, value, bucket);

                if (!removed) {
                  if (size <= 1) {
                    leftSibling = bucket.getLeftSibling();
                  } else {
                    leftSibling = -1;
                  }
                }
              } else {
                leftSibling = bucket.getLeftSibling();
              }
            } finally {
              releasePageFromWrite(atomicOperation, cacheEntry);
            }
          }

          while (!removed && rightSibling >= 0) {
            cacheEntry = loadPageForWrite(atomicOperation, fileId, rightSibling, false, true);
            try {
              final CellBTreeMultiValueV2Bucket<K> bucket = new CellBTreeMultiValueV2Bucket<>(cacheEntry);
              final int size = bucket.size();

              if (size > 0) {
                removed = removeEntry(0, serializeKeySize, value, bucket);

                if (!removed) {
                  if (size <= 1) {
                    rightSibling = bucket.getRightSibling();
                  } else {
                    rightSibling = -1;
                  }
                }
              } else {
                rightSibling = bucket.getRightSibling();
              }
            } finally {
              releasePageFromWrite(atomicOperation, cacheEntry);
            }
          }

          if (removed) {
            updateSize(-1, atomicOperation);

            @SuppressWarnings("RedundantCast")
            final byte[] serializedKey = keySerializer.serializeNativeAsWhole(key, (Object[]) keyTypes);
            atomicOperation.addComponentOperation(new OCellBtreeMultiValueRemoveEntryCO(indexId, keySerializer.getId(),
                (encryption != null ? encryption.name() : null), serializeKey(serializedKey), value));
          }
        } else {
          final OCacheEntry nullBucketCacheEntry = loadPageForWrite(atomicOperation, nullBucketFileId, 0, false, true);
          try {
            final CellBTreeMultiValueV2NullBucket nullBucket = new CellBTreeMultiValueV2NullBucket(nullBucketCacheEntry);
            final int result = nullBucket.removeValue(value);
            if (result == 0) {
              removed = multiContainer.remove(new MultiValueEntry(nullBucket.getMid(), value.getClusterId(), value.getClusterPosition()))
                  != null;
              if (removed) {
                nullBucket.decrementSize();
              }
            } else {
              removed = result == 1;
            }
          } finally {
            releasePageFromWrite(atomicOperation, nullBucketCacheEntry);
          }

          if (removed) {
            updateSize(-1, atomicOperation);

            atomicOperation.addComponentOperation(new OCellBtreeMultiValueRemoveEntryCO(indexId, keySerializer.getId(),
                (encryption != null ? encryption.name() : null), null, value));
          }
        }
      } finally {
        releaseExclusiveLock();
      }

      return removed;
    } catch (final RuntimeException | IOException e) {
      rollback = true;
      throw e;
    } finally {
      endAtomicOperation(rollback);
    }

  }

  private boolean removeEntry(final int itemIndex, final int keySize, final ORID value, final CellBTreeMultiValueV2Bucket<K> bucket)
      throws IOException {
    final int entriesCount = bucket.removeLeafEntry(itemIndex, value);
    if (entriesCount == 0) {
      bucket.removeMainLeafEntry(itemIndex, keySize);
    }

    boolean removed = entriesCount >= 0;
    if (!removed && bucket.hasExternalEntries(itemIndex)) {
      final long mId = bucket.getMid(itemIndex);
      removed = multiContainer.remove(new MultiValueEntry(mId, value.getClusterId(), value.getClusterPosition())) != null;
      if (removed) {
        if (bucket.decrementEntriesCount(itemIndex)) {
          bucket.removeMainLeafEntry(itemIndex, keySize);
        }
      }
    }

    return removed;
  }

  public OCellBTreeCursor<K, ORID> iterateEntriesMinor(final K key, final boolean inclusive, final boolean ascSortOrder) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        if (!ascSortOrder) {
          return iterateEntriesMinorDesc(key, inclusive);
        }

        return iterateEntriesMinorAsc(key, inclusive);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public OCellBTreeCursor<K, ORID> iterateEntriesMajor(final K key, final boolean inclusive, final boolean ascSortOrder) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        if (ascSortOrder) {
          return iterateEntriesMajorAsc(key, inclusive);
        }

        return iterateEntriesMajorDesc(key, inclusive);
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public K firstKey() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();

        final BucketSearchResult searchResult = firstItem(atomicOperation);
        if (searchResult == null) {
          return null;
        }

        final OCacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, searchResult.pageIndex, false);
        try {
          final CellBTreeMultiValueV2Bucket<K> bucket = new CellBTreeMultiValueV2Bucket<>(cacheEntry);
          return bucket.getKey(searchResult.itemIndex, keySerializer, encryption);
        } finally {
          releasePageFromRead(atomicOperation, cacheEntry);
        }
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException e) {
      throw OException
          .wrapException(new CellBTreeMultiValueException("Error during finding first key in sbtree [" + getName() + "]", this), e);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public K lastKey() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();

        final BucketSearchResult searchResult = lastItem(atomicOperation);
        if (searchResult == null) {
          return null;
        }

        final OCacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, searchResult.pageIndex, false);
        try {
          final CellBTreeMultiValueV2Bucket<K> bucket = new CellBTreeMultiValueV2Bucket<>(cacheEntry);
          return bucket.getKey(searchResult.itemIndex, keySerializer, encryption);
        } finally {
          releasePageFromRead(atomicOperation, cacheEntry);
        }
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException e) {
      throw OException
          .wrapException(new CellBTreeMultiValueException("Error during finding last key in sbtree [" + getName() + "]", this), e);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public OCellBTreeKeyCursor<K> keyCursor() {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();
        final BucketSearchResult searchResult = firstItem(atomicOperation);
        if (searchResult == null) {
          return prefetchSize -> null;
        }

        return new OCellBTreeFullKeyCursor(searchResult.pageIndex);
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException e) {
      throw OException
          .wrapException(new CellBTreeMultiValueException("Error during finding first key in sbtree [" + getName() + "]", this), e);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public OCellBTreeCursor<K, ORID> iterateEntriesBetween(final K keyFrom, final boolean fromInclusive, final K keyTo,
      final boolean toInclusive, final boolean ascSortOrder) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        if (ascSortOrder) {
          return iterateEntriesBetweenAscOrder(keyFrom, fromInclusive, keyTo, toInclusive);
        } else {
          return iterateEntriesBetweenDescOrder(keyFrom, fromInclusive, keyTo, toInclusive);
        }
      } finally {
        releaseSharedLock();
      }
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  /**
   * Acquires exclusive lock in the active atomic operation running on the current thread for this SB-tree.
   */
  public void acquireAtomicExclusiveLock() {
    atomicOperationsManager.acquireExclusiveLockTillOperationComplete(this);
  }

  private void updateSize(final long diffSize, final OAtomicOperation atomicOperation) throws IOException {
    final OCacheEntry entryPointCacheEntry = loadPageForWrite(atomicOperation, fileId, ENTRY_POINT_INDEX, false, true);
    try {
      final CellBTreeMultiValueV2EntryPoint<K> entryPoint = new CellBTreeMultiValueV2EntryPoint<>(entryPointCacheEntry);
      entryPoint.setTreeSize(entryPoint.getTreeSize() + diffSize);
    } finally {
      releasePageFromWrite(atomicOperation, entryPointCacheEntry);
    }
  }

  private OCellBTreeCursor<K, ORID> iterateEntriesMinorDesc(K key, final boolean inclusive) {
    //noinspection RedundantCast
    key = keySerializer.preprocess(key, (Object[]) keyTypes);
    key = enhanceCompositeKeyMinorDesc(key, inclusive);

    return new OCellBTreeCursorBackward(null, key, false, inclusive);
  }

  private OCellBTreeCursor<K, ORID> iterateEntriesMinorAsc(K key, final boolean inclusive) {
    //noinspection RedundantCast
    key = keySerializer.preprocess(key, (Object[]) keyTypes);
    key = enhanceCompositeKeyMinorAsc(key, inclusive);

    return new OCellBTreeCursorForward(null, key, false, inclusive);
  }

  private K enhanceCompositeKeyMinorDesc(K key, final boolean inclusive) {
    final PartialSearchMode partialSearchMode;
    if (inclusive) {
      partialSearchMode = PartialSearchMode.HIGHEST_BOUNDARY;
    } else {
      partialSearchMode = PartialSearchMode.LOWEST_BOUNDARY;
    }

    key = enhanceCompositeKey(key, partialSearchMode);
    return key;
  }

  private K enhanceCompositeKeyMinorAsc(K key, final boolean inclusive) {
    final PartialSearchMode partialSearchMode;
    if (inclusive) {
      partialSearchMode = PartialSearchMode.HIGHEST_BOUNDARY;
    } else {
      partialSearchMode = PartialSearchMode.LOWEST_BOUNDARY;
    }

    key = enhanceCompositeKey(key, partialSearchMode);
    return key;
  }

  private OCellBTreeCursor<K, ORID> iterateEntriesMajorAsc(K key, final boolean inclusive) {
    //noinspection RedundantCast
    key = keySerializer.preprocess(key, (Object[]) keyTypes);
    key = enhanceCompositeKeyMajorAsc(key, inclusive);

    return new OCellBTreeCursorForward(key, null, inclusive, false);
  }

  private OCellBTreeCursor<K, ORID> iterateEntriesMajorDesc(K key, final boolean inclusive) {
    acquireSharedLock();
    try {
      //noinspection RedundantCast
      key = keySerializer.preprocess(key, (Object[]) keyTypes);
      key = enhanceCompositeKeyMajorDesc(key, inclusive);

      return new OCellBTreeCursorBackward(key, null, inclusive, false);

    } finally {
      releaseSharedLock();
    }

  }

  private K enhanceCompositeKeyMajorAsc(K key, final boolean inclusive) {
    final PartialSearchMode partialSearchMode;
    if (inclusive) {
      partialSearchMode = PartialSearchMode.LOWEST_BOUNDARY;
    } else {
      partialSearchMode = PartialSearchMode.HIGHEST_BOUNDARY;
    }

    key = enhanceCompositeKey(key, partialSearchMode);
    return key;
  }

  private K enhanceCompositeKeyMajorDesc(K key, final boolean inclusive) {
    final PartialSearchMode partialSearchMode;
    if (inclusive) {
      partialSearchMode = PartialSearchMode.LOWEST_BOUNDARY;
    } else {
      partialSearchMode = PartialSearchMode.HIGHEST_BOUNDARY;
    }

    key = enhanceCompositeKey(key, partialSearchMode);
    return key;
  }

  private BucketSearchResult firstItem(final OAtomicOperation atomicOperation) throws IOException {
    final LinkedList<PagePathItemUnit> path = new LinkedList<>();

    long bucketIndex = ROOT_INDEX;

    OCacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, bucketIndex, false);
    int itemIndex = 0;
    try {
      CellBTreeMultiValueV2Bucket<K> bucket = new CellBTreeMultiValueV2Bucket<>(cacheEntry);

      while (true) {
        if (!bucket.isLeaf()) {
          if (bucket.isEmpty() || itemIndex > bucket.size()) {
            if (!path.isEmpty()) {
              final PagePathItemUnit pagePathItemUnit = path.removeLast();

              bucketIndex = pagePathItemUnit.pageIndex;
              itemIndex = pagePathItemUnit.itemIndex + 1;
            } else {
              return null;
            }
          } else {
            path.add(new PagePathItemUnit(bucketIndex, itemIndex));

            if (itemIndex < bucket.size()) {
              bucketIndex = bucket.getLeft(itemIndex);
            } else {
              bucketIndex = bucket.getRight(itemIndex - 1);
            }

            itemIndex = 0;
          }
        } else {
          if (bucket.isEmpty()) {
            if (!path.isEmpty()) {
              final PagePathItemUnit pagePathItemUnit = path.removeLast();

              bucketIndex = pagePathItemUnit.pageIndex;
              itemIndex = pagePathItemUnit.itemIndex + 1;
            } else {
              return null;
            }
          } else {
            return new BucketSearchResult(0, bucketIndex);
          }
        }

        releasePageFromRead(atomicOperation, cacheEntry);

        cacheEntry = loadPageForRead(atomicOperation, fileId, bucketIndex, false);

        bucket = new CellBTreeMultiValueV2Bucket<>(cacheEntry);
      }
    } finally {
      releasePageFromRead(atomicOperation, cacheEntry);
    }
  }

  private BucketSearchResult lastItem(final OAtomicOperation atomicOperation) throws IOException {
    final LinkedList<PagePathItemUnit> path = new LinkedList<>();

    long bucketIndex = ROOT_INDEX;

    OCacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, bucketIndex, false);

    CellBTreeMultiValueV2Bucket<K> bucket = new CellBTreeMultiValueV2Bucket<>(cacheEntry);

    int itemIndex = bucket.size() - 1;
    try {
      while (true) {
        if (!bucket.isLeaf()) {
          if (itemIndex < -1) {
            if (!path.isEmpty()) {
              final PagePathItemUnit pagePathItemUnit = path.removeLast();

              bucketIndex = pagePathItemUnit.pageIndex;
              itemIndex = pagePathItemUnit.itemIndex - 1;
            } else {
              return null;
            }
          } else {
            path.add(new PagePathItemUnit(bucketIndex, itemIndex));

            if (itemIndex > -1) {
              bucketIndex = bucket.getRight(itemIndex);
            } else {
              bucketIndex = bucket.getLeft(0);
            }

            itemIndex = CellBTreeMultiValueV2Bucket.MAX_PAGE_SIZE_BYTES + 1;
          }
        } else {
          if (bucket.isEmpty()) {
            if (!path.isEmpty()) {
              final PagePathItemUnit pagePathItemUnit = path.removeLast();

              bucketIndex = pagePathItemUnit.pageIndex;
              itemIndex = pagePathItemUnit.itemIndex - 1;
            } else {
              return null;
            }
          } else {
            return new BucketSearchResult(bucket.size() - 1, bucketIndex);
          }
        }

        releasePageFromRead(atomicOperation, cacheEntry);

        cacheEntry = loadPageForRead(atomicOperation, fileId, bucketIndex, false);

        bucket = new CellBTreeMultiValueV2Bucket<>(cacheEntry);
        if (itemIndex == CellBTreeMultiValueV2Bucket.MAX_PAGE_SIZE_BYTES + 1) {
          itemIndex = bucket.size() - 1;
        }
      }
    } finally {
      releasePageFromRead(atomicOperation, cacheEntry);
    }
  }

  private OCellBTreeCursor<K, ORID> iterateEntriesBetweenAscOrder(K keyFrom, final boolean fromInclusive, K keyTo,
      final boolean toInclusive) {
    //noinspection RedundantCast
    keyFrom = keySerializer.preprocess(keyFrom, (Object[]) keyTypes);
    //noinspection RedundantCast
    keyTo = keySerializer.preprocess(keyTo, (Object[]) keyTypes);

    keyFrom = enhanceFromCompositeKeyBetweenAsc(keyFrom, fromInclusive);
    keyTo = enhanceToCompositeKeyBetweenAsc(keyTo, toInclusive);

    return new OCellBTreeCursorForward(keyFrom, keyTo, fromInclusive, toInclusive);
  }

  private OCellBTreeCursor<K, ORID> iterateEntriesBetweenDescOrder(K keyFrom, final boolean fromInclusive, K keyTo,
      final boolean toInclusive) {
    //noinspection RedundantCast
    keyFrom = keySerializer.preprocess(keyFrom, (Object[]) keyTypes);
    //noinspection RedundantCast
    keyTo = keySerializer.preprocess(keyTo, (Object[]) keyTypes);

    keyFrom = enhanceFromCompositeKeyBetweenDesc(keyFrom, fromInclusive);
    keyTo = enhanceToCompositeKeyBetweenDesc(keyTo, toInclusive);

    return new OCellBTreeCursorBackward(keyFrom, keyTo, fromInclusive, toInclusive);
  }

  private K enhanceToCompositeKeyBetweenAsc(K keyTo, final boolean toInclusive) {
    final PartialSearchMode partialSearchModeTo;
    if (toInclusive) {
      partialSearchModeTo = PartialSearchMode.HIGHEST_BOUNDARY;
    } else {
      partialSearchModeTo = PartialSearchMode.LOWEST_BOUNDARY;
    }

    keyTo = enhanceCompositeKey(keyTo, partialSearchModeTo);
    return keyTo;
  }

  private K enhanceFromCompositeKeyBetweenAsc(K keyFrom, final boolean fromInclusive) {
    final PartialSearchMode partialSearchModeFrom;
    if (fromInclusive) {
      partialSearchModeFrom = PartialSearchMode.LOWEST_BOUNDARY;
    } else {
      partialSearchModeFrom = PartialSearchMode.HIGHEST_BOUNDARY;
    }

    keyFrom = enhanceCompositeKey(keyFrom, partialSearchModeFrom);
    return keyFrom;
  }

  private K enhanceToCompositeKeyBetweenDesc(K keyTo, final boolean toInclusive) {
    final PartialSearchMode partialSearchModeTo;
    if (toInclusive) {
      partialSearchModeTo = PartialSearchMode.HIGHEST_BOUNDARY;
    } else {
      partialSearchModeTo = PartialSearchMode.LOWEST_BOUNDARY;
    }

    keyTo = enhanceCompositeKey(keyTo, partialSearchModeTo);
    return keyTo;
  }

  private K enhanceFromCompositeKeyBetweenDesc(K keyFrom, final boolean fromInclusive) {
    final PartialSearchMode partialSearchModeFrom;
    if (fromInclusive) {
      partialSearchModeFrom = PartialSearchMode.LOWEST_BOUNDARY;
    } else {
      partialSearchModeFrom = PartialSearchMode.HIGHEST_BOUNDARY;
    }

    keyFrom = enhanceCompositeKey(keyFrom, partialSearchModeFrom);
    return keyFrom;
  }

  private UpdateBucketSearchResult splitBucket(final CellBTreeMultiValueV2Bucket<K> bucketToSplit, final OCacheEntry entryToSplit,
      final List<Long> path, final List<Integer> insertionIndexes, final int keyIndex, final K keyToInsert,
      final OAtomicOperation atomicOperation) throws IOException {
    final boolean splitLeaf = bucketToSplit.isLeaf();
    final int bucketSize = bucketToSplit.size();

    final int indexToSplit = bucketSize >>> 1;
    final byte[] serializedSeparationKey = bucketToSplit.getRawKey(indexToSplit, keySerializer, encryption);

    final List<CellBTreeMultiValueV2Bucket.Entry> rightEntries = new ArrayList<>(indexToSplit);

    final int startRightIndex = splitLeaf ? indexToSplit : indexToSplit + 1;

    if (splitLeaf) {
      for (int i = startRightIndex; i < bucketSize; i++) {
        rightEntries.add(bucketToSplit.getLeafEntry(i, keySerializer, encryption != null));
      }
    } else {
      for (int i = startRightIndex; i < bucketSize; i++) {
        rightEntries.add(bucketToSplit.getNonLeafEntry(i, keySerializer, encryption != null));
      }
    }

    if (entryToSplit.getPageIndex() != ROOT_INDEX) {
      return splitNonRootBucket(path, insertionIndexes, keyIndex, keyToInsert, entryToSplit.getPageIndex(), bucketToSplit,
          splitLeaf, indexToSplit, serializedSeparationKey, rightEntries, atomicOperation);
    } else {
      return splitRootBucket(keyIndex, keyToInsert, entryToSplit, bucketToSplit, splitLeaf, indexToSplit, serializedSeparationKey,
          rightEntries, atomicOperation);
    }
  }

  private byte[] serializeKey(final byte[] serializedKey) {
    final byte[] keyToInsert;
    if (encryption == null) {
      keyToInsert = serializedKey;
    } else {
      final byte[] encryptedKey = encryption.encrypt(serializedKey);

      keyToInsert = new byte[OIntegerSerializer.INT_SIZE + encryptedKey.length];
      OIntegerSerializer.INSTANCE.serializeNative(encryptedKey.length, keyToInsert, 0);
      System.arraycopy(encryptedKey, 0, keyToInsert, OIntegerSerializer.INT_SIZE, encryptedKey.length);
    }
    return keyToInsert;
  }

  private K deserializeKey(final byte[] serializedKey) {
    if (encryption == null) {
      return keySerializer.deserializeNativeObject(serializedKey, 0);
    }

    final byte[] decrypted = encryption
        .decrypt(serializedKey, OIntegerSerializer.INT_SIZE, serializedKey.length - OIntegerSerializer.INT_SIZE);
    return keySerializer.deserializeNativeObject(decrypted, 0);
  }

  private UpdateBucketSearchResult splitNonRootBucket(final List<Long> path, final List<Integer> insertionIndexes,
      final int keyIndex, final K keyToInsert, final long pageIndex, final CellBTreeMultiValueV2Bucket<K> bucketToSplit,
      final boolean splitLeaf, final int indexToSplit, final byte[] serializedSeparationKey,
      final List<CellBTreeMultiValueV2Bucket.Entry> rightEntries, final OAtomicOperation atomicOperation) throws IOException {

    final OCacheEntry rightBucketEntry;
    final OCacheEntry entryPointCacheEntry = loadPageForWrite(atomicOperation, fileId, ENTRY_POINT_INDEX, false, true);
    try {
      final CellBTreeMultiValueV2EntryPoint<K> entryPoint = new CellBTreeMultiValueV2EntryPoint<>(entryPointCacheEntry);
      final int pageSize = entryPoint.getPagesSize();

      if (pageSize < getFilledUpTo(atomicOperation, fileId) - 1) {
        rightBucketEntry = loadPageForWrite(atomicOperation, fileId, pageSize, false, false);
        entryPoint.setPagesSize(pageSize + 1);
      } else {
        assert pageSize == getFilledUpTo(atomicOperation, fileId) - 1;

        rightBucketEntry = addPage(atomicOperation, fileId);
        entryPoint.setPagesSize(rightBucketEntry.getPageIndex());
      }
    } finally {
      releasePageFromWrite(atomicOperation, entryPointCacheEntry);
    }

    K separationKey = null;

    try {
      final CellBTreeMultiValueV2Bucket<K> newRightBucket = new CellBTreeMultiValueV2Bucket<>(rightBucketEntry);
      newRightBucket.init(splitLeaf);

      newRightBucket.addAll(rightEntries, keySerializer, encryption != null);

      assert bucketToSplit.size() > 1;
      bucketToSplit.shrink(indexToSplit, keySerializer, encryption != null);

      if (splitLeaf) {
        final long rightSiblingPageIndex = bucketToSplit.getRightSibling();

        newRightBucket.setRightSibling(rightSiblingPageIndex);
        newRightBucket.setLeftSibling(pageIndex);

        bucketToSplit.setRightSibling(rightBucketEntry.getPageIndex());

        if (rightSiblingPageIndex >= 0) {
          final OCacheEntry rightSiblingBucketEntry = loadPageForWrite(atomicOperation, fileId, rightSiblingPageIndex, false, true);
          final CellBTreeMultiValueV2Bucket<K> rightSiblingBucket = new CellBTreeMultiValueV2Bucket<>(rightSiblingBucketEntry);
          try {
            rightSiblingBucket.setLeftSibling(rightBucketEntry.getPageIndex());
          } finally {
            releasePageFromWrite(atomicOperation, rightSiblingBucketEntry);
          }
        }
      }

      long parentIndex = path.get(path.size() - 2);
      OCacheEntry parentCacheEntry = loadPageForWrite(atomicOperation, fileId, parentIndex, false, true);
      try {
        CellBTreeMultiValueV2Bucket<K> parentBucket = new CellBTreeMultiValueV2Bucket<>(parentCacheEntry);
        int insertionIndex = insertionIndexes.get(insertionIndexes.size() - 2);

        while (!parentBucket
            .addNonLeafEntry(insertionIndex, serializedSeparationKey, (int) pageIndex, rightBucketEntry.getPageIndex(), true)) {
          if (separationKey == null) {
            separationKey = deserializeKey(serializedSeparationKey);
          }

          final UpdateBucketSearchResult bucketSearchResult = splitBucket(parentBucket, parentCacheEntry,
              path.subList(0, path.size() - 1), insertionIndexes.subList(0, insertionIndexes.size() - 1), insertionIndex,
              separationKey, atomicOperation);

          parentIndex = bucketSearchResult.getLastPathItem();
          insertionIndex = bucketSearchResult.itemIndex;

          if (parentIndex != parentCacheEntry.getPageIndex()) {
            releasePageFromWrite(atomicOperation, parentCacheEntry);
            parentCacheEntry = loadPageForWrite(atomicOperation, fileId, parentIndex, false, true);
          }

          parentBucket = new CellBTreeMultiValueV2Bucket<>(parentCacheEntry);
        }

      } finally {
        releasePageFromWrite(atomicOperation, parentCacheEntry);
      }

    } finally {
      releasePageFromWrite(atomicOperation, rightBucketEntry);
    }

    final ArrayList<Long> resultPath = new ArrayList<>(path.subList(0, path.size() - 1));
    final ArrayList<Integer> resultInsertionIndexes = new ArrayList<>(insertionIndexes.subList(0, insertionIndexes.size() - 1));

    if (keyIndex < indexToSplit) {
      return addToTheLeftNonRootBucket(keyIndex, pageIndex, resultPath, resultInsertionIndexes);
    } else if (keyIndex > indexToSplit) {
      return addToTheRightNonRootBucket(keyIndex, splitLeaf, indexToSplit, rightBucketEntry.getPageIndex(), resultPath,
          resultInsertionIndexes);
    } else if (splitLeaf && keyToInsert.equals(separationKey != null ? separationKey : deserializeKey(serializedSeparationKey))) {
      return addToTheRightNonRootBucket(keyIndex, true, indexToSplit, rightBucketEntry.getPageIndex(), resultPath,
          resultInsertionIndexes);
    } else {
      return addToTheLeftNonRootBucket(keyIndex, pageIndex, resultPath, resultInsertionIndexes);
    }
  }

  private static UpdateBucketSearchResult addToTheRightNonRootBucket(final int keyIndex, final boolean splitLeaf,
      final int indexToSplit, final long rightPageIndex, final ArrayList<Long> resultPath,
      final ArrayList<Integer> resultItemPointers) {
    final int parentIndex = resultItemPointers.size() - 1;
    resultItemPointers.set(parentIndex, resultItemPointers.get(parentIndex) + 1);
    resultPath.add(rightPageIndex);

    if (splitLeaf) {
      resultItemPointers.add(keyIndex - indexToSplit);
      return new UpdateBucketSearchResult(resultItemPointers, resultPath, keyIndex - indexToSplit);
    }

    final int insertionIndex = keyIndex - indexToSplit - 1;

    resultItemPointers.add(insertionIndex);
    return new UpdateBucketSearchResult(resultItemPointers, resultPath, insertionIndex);
  }

  private static UpdateBucketSearchResult addToTheLeftNonRootBucket(final int keyIndex, final long pageIndex,
      final ArrayList<Long> resultPath, final ArrayList<Integer> resultItemPointers) {
    resultPath.add(pageIndex);
    resultItemPointers.add(keyIndex);

    return new UpdateBucketSearchResult(resultItemPointers, resultPath, keyIndex);
  }

  private UpdateBucketSearchResult splitRootBucket(final int keyIndex, final K keyToInsert, final OCacheEntry bucketEntry,
      CellBTreeMultiValueV2Bucket<K> bucketToSplit, final boolean splitLeaf, final int indexToSplit,
      final byte[] serializedSeparationKey, final List<CellBTreeMultiValueV2Bucket.Entry> rightEntries,
      final OAtomicOperation atomicOperation) throws IOException {
    final List<CellBTreeMultiValueV2Bucket.Entry> leftEntries = new ArrayList<>(indexToSplit);

    if (splitLeaf) {
      if (bucketToSplit.size() > 1) {
        for (int i = 0; i < indexToSplit; i++) {
          leftEntries.add(bucketToSplit.getLeafEntry(i, keySerializer, encryption != null));
        }
      } else {
        throw new IllegalStateException("Bucket should have at least two entries to be able to split");
      }
    } else {
      for (int i = 0; i < indexToSplit; i++) {
        leftEntries.add(bucketToSplit.getNonLeafEntry(i, keySerializer, encryption != null));
      }
    }

    final OCacheEntry leftBucketEntry;
    final OCacheEntry rightBucketEntry;

    final OCacheEntry entryPointCacheEntry = loadPageForWrite(atomicOperation, fileId, ENTRY_POINT_INDEX, false, true);
    try {
      final CellBTreeMultiValueV2EntryPoint<K> entryPoint = new CellBTreeMultiValueV2EntryPoint<>(entryPointCacheEntry);
      int pageSize = entryPoint.getPagesSize();

      final int filledUpTo = (int) getFilledUpTo(atomicOperation, fileId);

      if (pageSize < filledUpTo - 1) {
        leftBucketEntry = loadPageForWrite(atomicOperation, fileId, pageSize, false, false);
        pageSize++;
      } else {
        assert pageSize == filledUpTo - 1;

        leftBucketEntry = addPage(atomicOperation, fileId);
        pageSize = leftBucketEntry.getPageIndex();
      }

      if (pageSize < filledUpTo) {
        rightBucketEntry = loadPageForWrite(atomicOperation, fileId, pageSize, false, false);
        pageSize++;
      } else {
        assert pageSize == filledUpTo;

        rightBucketEntry = addPage(atomicOperation, fileId);
        pageSize = rightBucketEntry.getPageIndex();
      }

      entryPoint.setPagesSize(pageSize);
    } finally {
      releasePageFromWrite(atomicOperation, entryPointCacheEntry);
    }

    try {
      final CellBTreeMultiValueV2Bucket<K> newLeftBucket = new CellBTreeMultiValueV2Bucket<>(leftBucketEntry);
      newLeftBucket.init(splitLeaf);

      newLeftBucket.addAll(leftEntries, keySerializer, encryption != null);

      if (splitLeaf) {
        newLeftBucket.setRightSibling(rightBucketEntry.getPageIndex());
      }

    } finally {
      releasePageFromWrite(atomicOperation, leftBucketEntry);
    }

    try {
      final CellBTreeMultiValueV2Bucket<K> newRightBucket = new CellBTreeMultiValueV2Bucket<>(rightBucketEntry);
      newRightBucket.init(splitLeaf);

      newRightBucket.addAll(rightEntries, keySerializer, encryption != null);

      if (splitLeaf) {
        newRightBucket.setLeftSibling(leftBucketEntry.getPageIndex());
      }
    } finally {
      releasePageFromWrite(atomicOperation, rightBucketEntry);
    }

    bucketToSplit = new CellBTreeMultiValueV2Bucket<>(bucketEntry);
    bucketToSplit.init(false);

    bucketToSplit
        .addNonLeafEntry(0, serializedSeparationKey, leftBucketEntry.getPageIndex(), rightBucketEntry.getPageIndex(), true);

    final ArrayList<Long> resultPath = new ArrayList<>(8);
    resultPath.add(ROOT_INDEX);

    final ArrayList<Integer> itemPointers = new ArrayList<>(8);

    if (keyIndex < indexToSplit) {
      return addToTheLeftRootBucket(keyIndex, leftBucketEntry, resultPath, itemPointers);
    } else if (keyIndex > indexToSplit) {
      return addToTheRightRootBucket(keyIndex, splitLeaf, indexToSplit, rightBucketEntry, resultPath, itemPointers);
    } else if (splitLeaf && keyToInsert.equals(deserializeKey(serializedSeparationKey))) {
      return addToTheRightRootBucket(keyIndex, true, indexToSplit, rightBucketEntry, resultPath, itemPointers);
    } else {
      return addToTheLeftRootBucket(keyIndex, leftBucketEntry, resultPath, itemPointers);
    }
  }

  private static UpdateBucketSearchResult addToTheRightRootBucket(final int keyIndex, final boolean splitLeaf,
      final int indexToSplit, final OCacheEntry rightBucketEntry, final ArrayList<Long> resultPath,
      final ArrayList<Integer> itemPointers) {
    resultPath.add((long) rightBucketEntry.getPageIndex());
    itemPointers.add(0);

    if (splitLeaf) {
      itemPointers.add(keyIndex - indexToSplit);
      return new UpdateBucketSearchResult(itemPointers, resultPath, keyIndex - indexToSplit);
    }

    final int itemPointer = keyIndex - indexToSplit - 1;
    itemPointers.add(itemPointer);

    return new UpdateBucketSearchResult(itemPointers, resultPath, itemPointer);
  }

  private static UpdateBucketSearchResult addToTheLeftRootBucket(final int keyIndex, final OCacheEntry leftBucketEntry,
      final ArrayList<Long> resultPath, final ArrayList<Integer> itemPointers) {
    itemPointers.add(-1);
    itemPointers.add(keyIndex);

    resultPath.add((long) leftBucketEntry.getPageIndex());
    return new UpdateBucketSearchResult(itemPointers, resultPath, keyIndex);
  }

  private BucketSearchResult findBucket(final K key, final OAtomicOperation atomicOperation) throws IOException {
    long pageIndex = ROOT_INDEX;

    int depth = 0;
    while (true) {
      depth++;
      if (depth > MAX_PATH_LENGTH) {
        throw new CellBTreeMultiValueException(
            "We reached max level of depth of SBTree but still found nothing, seems like tree is in corrupted state. You should rebuild index related to given query.",
            this);
      }

      final OCacheEntry bucketEntry = loadPageForRead(atomicOperation, fileId, pageIndex, false);
      try {
        final CellBTreeMultiValueV2Bucket<K> keyBucket = new CellBTreeMultiValueV2Bucket<>(bucketEntry);
        final int index = keyBucket.find(key, keySerializer, encryption);

        if (keyBucket.isLeaf()) {
          return new BucketSearchResult(index, pageIndex);
        }

        if (index >= 0) {
          pageIndex = keyBucket.getRight(index);
        } else {
          final int insertionIndex = -index - 1;
          if (insertionIndex >= keyBucket.size()) {
            pageIndex = keyBucket.getRight(insertionIndex - 1);
          } else {
            pageIndex = keyBucket.getLeft(insertionIndex);
          }
        }
      } finally {
        releasePageFromRead(atomicOperation, bucketEntry);
      }
    }
  }

  private UpdateBucketSearchResult findBucketForUpdate(final K key, final OAtomicOperation atomicOperation) throws IOException {
    long pageIndex = ROOT_INDEX;

    final ArrayList<Long> path = new ArrayList<>(8);
    final ArrayList<Integer> insertionIndexes = new ArrayList<>(8);

    while (true) {
      if (path.size() > MAX_PATH_LENGTH) {
        throw new CellBTreeMultiValueException(
            "We reached max level of depth of SBTree but still found nothing, seems like tree is in corrupted state. You should rebuild index related to given query.",
            this);
      }

      path.add(pageIndex);
      final OCacheEntry bucketEntry = loadPageForRead(atomicOperation, fileId, pageIndex, false);
      try {
        final CellBTreeMultiValueV2Bucket<K> keyBucket = new CellBTreeMultiValueV2Bucket<>(bucketEntry);
        final int index = keyBucket.find(key, keySerializer, encryption);

        if (keyBucket.isLeaf()) {
          insertionIndexes.add(index);
          return new UpdateBucketSearchResult(insertionIndexes, path, index);
        }

        if (index >= 0) {
          pageIndex = keyBucket.getRight(index);
          insertionIndexes.add(index + 1);
        } else {
          final int insertionIndex = -index - 1;

          if (insertionIndex >= keyBucket.size()) {
            pageIndex = keyBucket.getRight(insertionIndex - 1);
          } else {
            pageIndex = keyBucket.getLeft(insertionIndex);
          }

          insertionIndexes.add(insertionIndex);
        }
      } finally {
        releasePageFromRead(atomicOperation, bucketEntry);
      }
    }
  }

  private K enhanceCompositeKey(final K key, final PartialSearchMode partialSearchMode) {
    if (!(key instanceof OCompositeKey)) {
      return key;
    }

    final OCompositeKey compositeKey = (OCompositeKey) key;

    if (!(keySize == 1 || compositeKey.getKeys().size() == keySize || partialSearchMode.equals(PartialSearchMode.NONE))) {
      final OCompositeKey fullKey = new OCompositeKey(compositeKey);
      final int itemsToAdd = keySize - fullKey.getKeys().size();

      final Comparable<?> keyItem;
      if (partialSearchMode.equals(PartialSearchMode.HIGHEST_BOUNDARY)) {
        keyItem = ALWAYS_GREATER_KEY;
      } else {
        keyItem = ALWAYS_LESS_KEY;
      }

      for (int i = 0; i < itemsToAdd; i++) {
        fullKey.addKey(keyItem);
      }

      //noinspection unchecked
      return (K) fullKey;
    }

    return key;
  }

  /**
   * Indicates search behavior in case of {@link OCompositeKey} keys that have less amount of internal keys are used, whether lowest
   * or highest partially matched key should be used.
   */
  private enum PartialSearchMode {
    /**
     * Any partially matched key will be used as search result.
     */
    NONE,
    /**
     * The biggest partially matched key will be used as search result.
     */
    HIGHEST_BOUNDARY,

    /**
     * The smallest partially matched key will be used as search result.
     */
    LOWEST_BOUNDARY
  }

  private static final class BucketSearchResult {
    private final int  itemIndex;
    private final long pageIndex;

    private BucketSearchResult(final int itemIndex, final long pageIndex) {
      this.itemIndex = itemIndex;
      this.pageIndex = pageIndex;
    }
  }

  private static final class UpdateBucketSearchResult {
    private final List<Integer>   insertionIndexes;
    private final ArrayList<Long> path;
    private final int             itemIndex;

    private UpdateBucketSearchResult(final List<Integer> insertionIndexes, final ArrayList<Long> path, final int itemIndex) {
      this.insertionIndexes = insertionIndexes;
      this.path = path;
      this.itemIndex = itemIndex;
    }

    final long getLastPathItem() {
      return path.get(path.size() - 1);
    }
  }

  private static final class PagePathItemUnit {
    private final long pageIndex;
    private final int  itemIndex;

    private PagePathItemUnit(final long pageIndex, final int itemIndex) {
      this.pageIndex = pageIndex;
      this.itemIndex = itemIndex;
    }
  }

  public final class OCellBTreeFullKeyCursor implements OCellBTreeKeyCursor<K> {
    private long pageIndex;
    private int  itemIndex;

    private List<K>     keysCache    = new ArrayList<>();
    private Iterator<K> keysIterator = new OEmptyIterator<>();

    OCellBTreeFullKeyCursor(final long startPageIndex) {
      pageIndex = startPageIndex;
      itemIndex = 0;
    }

    @Override
    public K next(int prefetchSize) {
      if (keysIterator == null) {
        return null;
      }

      if (keysIterator.hasNext()) {
        return keysIterator.next();
      }

      keysCache.clear();

      if (prefetchSize < 0 || prefetchSize > OGlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger()) {
        prefetchSize = OGlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger();
      }

      if (prefetchSize == 0) {
        prefetchSize = 1;
      }

      atomicOperationsManager.acquireReadLock(CellBTreeMultiValueV2.this);
      try {
        acquireSharedLock();
        try {
          final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();

          while (keysCache.size() < prefetchSize) {
            if (pageIndex == -1) {
              break;
            }

            final OCacheEntry entryPointCacheEntry = loadPageForRead(atomicOperation, fileId, ENTRY_POINT_INDEX, false);
            try {
              final CellBTreeMultiValueV2EntryPoint<K> entryPoint = new CellBTreeMultiValueV2EntryPoint<>(entryPointCacheEntry);
              if (pageIndex >= entryPoint.getPagesSize() + 1) {
                pageIndex = -1;
                break;
              }
            } finally {
              releasePageFromRead(atomicOperation, entryPointCacheEntry);
            }

            final OCacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex, false);
            try {
              final CellBTreeMultiValueV2Bucket<K> bucket = new CellBTreeMultiValueV2Bucket<>(cacheEntry);

              final int bucketSize = bucket.size();
              if (itemIndex >= bucketSize) {
                pageIndex = bucket.getRightSibling();
                itemIndex = 0;
                continue;
              }

              while (itemIndex < bucketSize && keysCache.size() < prefetchSize) {
                keysCache.add(deserializeKey(bucket.getRawKey(itemIndex, keySerializer, encryption)));
                itemIndex++;
              }
            } finally {
              releasePageFromRead(atomicOperation, cacheEntry);
            }
          }
        } finally {
          releaseSharedLock();
        }
      } catch (final IOException e) {
        throw OException
            .wrapException(new CellBTreeMultiValueException("Error during element iteration", CellBTreeMultiValueV2.this), e);
      } finally {
        atomicOperationsManager.releaseReadLock(CellBTreeMultiValueV2.this);
      }

      if (keysCache.isEmpty()) {
        keysCache = null;
        return null;
      }

      keysIterator = keysCache.iterator();
      return keysIterator.next();
    }
  }

  private final class OCellBTreeCursorForward implements OCellBTreeCursor<K, ORID> {
    private       K       fromKey;
    private final K       toKey;
    private       boolean fromKeyInclusive;
    private final boolean toKeyInclusive;

    private final List<Map.Entry<K, ORID>>     dataCache         = new ArrayList<>();
    @SuppressWarnings("unchecked")
    private       Iterator<Map.Entry<K, ORID>> dataCacheIterator = OEmptyMapEntryIterator.INSTANCE;

    private OCellBTreeCursorForward(final K fromKey, final K toKey, final boolean fromKeyInclusive, final boolean toKeyInclusive) {
      this.fromKey = fromKey;
      this.toKey = toKey;
      this.fromKeyInclusive = fromKeyInclusive;
      this.toKeyInclusive = toKeyInclusive;

      if (fromKey == null) {
        this.fromKeyInclusive = true;
      }
    }

    public Map.Entry<K, ORID> next(int prefetchSize) {
      if (dataCacheIterator == null) {
        return null;
      }

      if (dataCacheIterator.hasNext()) {
        final Map.Entry<K, ORID> entry = dataCacheIterator.next();

        fromKey = entry.getKey();
        fromKeyInclusive = false;

        return entry;
      }

      dataCache.clear();

      if (prefetchSize < 0 || prefetchSize > OGlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger()) {
        prefetchSize = OGlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger();
      }

      if (prefetchSize == 0) {
        prefetchSize = 1;
      }

      atomicOperationsManager.acquireReadLock(CellBTreeMultiValueV2.this);
      try {
        acquireSharedLock();
        try {
          final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();

          final BucketSearchResult bucketSearchResult;

          if (fromKey != null) {
            bucketSearchResult = findBucket(fromKey, atomicOperation);
          } else {
            bucketSearchResult = firstItem(atomicOperation);
          }

          if (bucketSearchResult == null) {
            dataCacheIterator = null;
            return null;
          }

          long pageIndex = bucketSearchResult.pageIndex;
          int itemIndex;

          if (bucketSearchResult.itemIndex >= 0) {
            itemIndex = fromKeyInclusive ? bucketSearchResult.itemIndex : bucketSearchResult.itemIndex + 1;
          } else {
            itemIndex = -bucketSearchResult.itemIndex - 1;
          }

          K lastKey = null;

          boolean firstTry = true;
          mainCycle:
          while (true) {
            if (pageIndex == -1) {
              break;
            }

            final OCacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex, false);
            try {
              final CellBTreeMultiValueV2Bucket<K> bucket = new CellBTreeMultiValueV2Bucket<>(cacheEntry);
              if (firstTry && fromKey != null && fromKeyInclusive && bucketSearchResult.itemIndex == 0) {
                int leftSibling = (int) bucket.getLeftSibling();
                while (leftSibling > 0) {
                  final OCacheEntry siblingCacheEntry = loadPageForRead(atomicOperation, fileId, leftSibling, false);
                  final CellBTreeMultiValueV2Bucket<K> siblingBucket = new CellBTreeMultiValueV2Bucket<>(siblingCacheEntry);

                  final int bucketSize = siblingBucket.size();
                  if (bucketSize == 0) {
                    leftSibling = (int) siblingBucket.getLeftSibling();
                  } else if (bucketSize == 1) {
                    final K key = siblingBucket.getKey(0, keySerializer, encryption);

                    if (key.equals(fromKey)) {
                      lastKey = key;

                      fetchMapEntries(0, key, dataCache, siblingBucket);

                      leftSibling = (int) siblingBucket.getLeftSibling();
                    } else {
                      leftSibling = -1;
                    }
                  } else {
                    final K key = siblingBucket.getKey(bucketSize - 1, keySerializer, encryption);
                    if (key.equals(fromKey)) {
                      lastKey = key;

                      fetchMapEntries(0, key, dataCache, siblingBucket);

                      leftSibling = -1;
                    } else {
                      leftSibling = -1;
                    }
                  }

                  releasePageFromRead(atomicOperation, siblingCacheEntry);
                }
              }

              firstTry = false;

              while (true) {
                if (itemIndex >= bucket.size()) {
                  pageIndex = bucket.getRightSibling();
                  itemIndex = 0;
                  continue mainCycle;
                }

                final CellBTreeMultiValueV2Bucket.LeafEntry leafEntry = bucket
                    .getLeafEntry(itemIndex, keySerializer, encryption != null);
                itemIndex++;

                final K key = deserializeKey(leafEntry.key);
                if (dataCache.size() >= prefetchSize && (lastKey == null || !lastKey.equals(key))) {
                  break mainCycle;
                }

                if (fromKeyInclusive) {
                  if (fromKey != null && comparator.compare(key, fromKey) < 0) {
                    continue;
                  }
                } else {
                  if (fromKey != null && comparator.compare(key, fromKey) <= 0) {
                    continue;
                  }
                }

                if (toKeyInclusive) {
                  if (toKey != null && comparator.compare(key, toKey) > 0) {
                    break mainCycle;
                  }
                } else {
                  if (toKey != null && comparator.compare(key, toKey) >= 0) {
                    break mainCycle;
                  }
                }

                lastKey = key;
                fetchMapEntriesFromLeafEntry(key, dataCache, leafEntry);
              }

            } finally {
              releasePageFromRead(atomicOperation, cacheEntry);
            }
          }
        } finally {
          releaseSharedLock();
        }
      } catch (final IOException e) {
        throw OException
            .wrapException(new CellBTreeMultiValueException("Error during element iteration", CellBTreeMultiValueV2.this), e);
      } finally {
        atomicOperationsManager.releaseReadLock(CellBTreeMultiValueV2.this);
      }

      if (dataCache.isEmpty()) {
        dataCacheIterator = null;
        return null;
      }

      dataCacheIterator = dataCache.iterator();

      final Map.Entry<K, ORID> entry = dataCacheIterator.next();

      fromKey = entry.getKey();
      fromKeyInclusive = false;

      return entry;
    }
  }

  private final class OCellBTreeCursorBackward implements OCellBTreeCursor<K, ORID> {
    private final K       fromKey;
    private       K       toKey;
    private final boolean fromKeyInclusive;
    private       boolean toKeyInclusive;

    private final List<Map.Entry<K, ORID>>     dataCache         = new ArrayList<>();
    @SuppressWarnings("unchecked")
    private       Iterator<Map.Entry<K, ORID>> dataCacheIterator = OEmptyMapEntryIterator.INSTANCE;

    private OCellBTreeCursorBackward(final K fromKey, final K toKey, final boolean fromKeyInclusive, final boolean toKeyInclusive) {
      this.fromKey = fromKey;
      this.toKey = toKey;
      this.fromKeyInclusive = fromKeyInclusive;
      this.toKeyInclusive = toKeyInclusive;

      if (toKey == null) {
        this.toKeyInclusive = true;
      }

    }

    public Map.Entry<K, ORID> next(int prefetchSize) {
      if (dataCacheIterator == null) {
        return null;
      }

      if (dataCacheIterator.hasNext()) {
        final Map.Entry<K, ORID> entry = dataCacheIterator.next();
        toKey = entry.getKey();

        toKeyInclusive = false;
        return entry;
      }

      dataCache.clear();

      if (prefetchSize < 0 || prefetchSize > OGlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger()) {
        prefetchSize = OGlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger();
      }

      atomicOperationsManager.acquireReadLock(CellBTreeMultiValueV2.this);
      try {
        acquireSharedLock();
        try {
          final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();

          final BucketSearchResult bucketSearchResult;

          if (toKey != null) {
            bucketSearchResult = findBucket(toKey, atomicOperation);
          } else {
            bucketSearchResult = lastItem(atomicOperation);
          }

          if (bucketSearchResult == null) {
            dataCacheIterator = null;
            return null;
          }

          long pageIndex = bucketSearchResult.pageIndex;

          int itemIndex;
          if (bucketSearchResult.itemIndex >= 0) {
            itemIndex = toKeyInclusive ? bucketSearchResult.itemIndex : bucketSearchResult.itemIndex - 1;
          } else {
            itemIndex = -bucketSearchResult.itemIndex - 2;
          }

          boolean firstTry = true;
          K lastKey = null;
          mainCycle:
          while (true) {
            if (pageIndex == -1) {
              break;
            }

            final OCacheEntry cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex, false);
            try {
              final CellBTreeMultiValueV2Bucket<K> bucket = new CellBTreeMultiValueV2Bucket<>(cacheEntry);
              if (firstTry && toKey != null && toKeyInclusive && bucketSearchResult.itemIndex == 0) {
                int rightSibling = (int) bucket.getRightSibling();
                while (rightSibling > 0) {
                  final OCacheEntry siblingCacheEntry = loadPageForRead(atomicOperation, fileId, rightSibling, false);
                  final CellBTreeMultiValueV2Bucket<K> siblingBucket = new CellBTreeMultiValueV2Bucket<>(siblingCacheEntry);

                  final int bucketSize = siblingBucket.size();
                  if (bucketSize == 0) {
                    rightSibling = (int) siblingBucket.getRightSibling();
                  } else if (bucketSize == 1) {
                    final K key = siblingBucket.getKey(0, keySerializer, encryption);

                    if (key.equals(fromKey)) {
                      lastKey = key;

                      fetchMapEntries(0, key, dataCache, siblingBucket);
                      rightSibling = (int) siblingBucket.getRightSibling();
                    } else {
                      rightSibling = -1;
                    }
                  } else {
                    final K key = siblingBucket.getKey(0, keySerializer, encryption);
                    if (key.equals(fromKey)) {
                      lastKey = key;

                      fetchMapEntries(0, key, dataCache, siblingBucket);
                    } else {
                      rightSibling = -1;
                    }

                    releasePageFromRead(atomicOperation, siblingCacheEntry);
                  }
                }
              }

              firstTry = false;

              while (true) {
                if (itemIndex >= bucket.size()) {
                  itemIndex = bucket.size() - 1;
                }

                if (itemIndex < 0) {
                  pageIndex = bucket.getLeftSibling();
                  itemIndex = Integer.MAX_VALUE;
                  continue mainCycle;
                }

                final CellBTreeMultiValueV2Bucket.LeafEntry leafEntry = bucket
                    .getLeafEntry(itemIndex, keySerializer, encryption != null);
                itemIndex--;

                final K key = deserializeKey(leafEntry.key);
                if (dataCache.size() >= prefetchSize && (lastKey == null || !lastKey.equals(key))) {
                  break mainCycle;
                }

                if (toKeyInclusive) {
                  if (toKey != null && comparator.compare(key, toKey) > 0) {
                    continue;
                  }
                } else {
                  if (toKey != null && comparator.compare(key, toKey) >= 0) {
                    continue;
                  }
                }

                if (fromKeyInclusive) {
                  if (fromKey != null && comparator.compare(key, fromKey) < 0) {
                    break mainCycle;
                  }
                } else {
                  if (fromKey != null && comparator.compare(key, fromKey) <= 0) {
                    break mainCycle;
                  }
                }

                lastKey = key;

                fetchMapEntriesFromLeafEntry(key, dataCache, leafEntry);
              }
            } finally {
              releasePageFromRead(atomicOperation, cacheEntry);
            }
          }
        } finally {
          releaseSharedLock();
        }
      } catch (final IOException e) {
        throw OException
            .wrapException(new CellBTreeMultiValueException("Error during element iteration", CellBTreeMultiValueV2.this), e);
      } finally {
        atomicOperationsManager.releaseReadLock(CellBTreeMultiValueV2.this);
      }

      if (dataCache.isEmpty()) {
        dataCacheIterator = null;
        return null;
      }

      dataCacheIterator = dataCache.iterator();

      final Map.Entry<K, ORID> entry = dataCacheIterator.next();

      toKey = entry.getKey();
      toKeyInclusive = false;

      return entry;
    }
  }
}
