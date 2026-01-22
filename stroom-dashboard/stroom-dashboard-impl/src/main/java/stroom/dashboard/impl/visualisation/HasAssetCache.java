/*
 * Copyright 2016-2026 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.dashboard.impl.visualisation;

/**
 * Interface for thing that has an asset cache.
 */
public interface HasAssetCache {

    /**
     * Locks the cache to prevent any access to the cache.
     * BE CAREFUL and ensure that this gets unlocked again!
     * Lock is re-entrant so safe to lock more than once.
     */
    void lockCacheForDoc(String docId);

    /**
     * Unlocks the cache to allow access to the cache.
     * BE CAREFUL and ensure this was locked.
     * Lock is re-entrant so safe to lock more than once.
     */
    void unlockCacheForDoc(String docId);

    /**
     * Invalidates the cache for the given document ID.
     * Lock is re-entrant so safe to lock more than once.
     */
    void invalidateCacheForDoc(String docId);
}
