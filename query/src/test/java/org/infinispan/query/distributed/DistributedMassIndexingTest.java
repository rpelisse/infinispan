/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @author tag. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */
package org.infinispan.query.distributed;

import java.util.ArrayList;
import java.util.List;

import junit.framework.Assert;

import org.apache.lucene.search.Query;
import org.hibernate.search.query.dsl.QueryBuilder;
import org.infinispan.Cache;
import org.infinispan.AdvancedCache;
import org.infinispan.context.Flag;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.query.CacheQuery;
import org.infinispan.query.Search;
import org.infinispan.query.SearchManager;
import org.infinispan.query.queries.faceting.Car;
import org.infinispan.test.MultipleCacheManagersTest;
import org.infinispan.test.fwk.TestCacheManagerFactory;
import org.testng.annotations.Test;

/**
 * @author Sanne Grinovero <sanne@hibernate.org> (C) 2012 Red Hat Inc.
 */
@Test(groups = "functional", testName = "query.distributed.DistributedMassIndexing")
public class DistributedMassIndexingTest extends MultipleCacheManagersTest {

   protected static final int NUM_NODES = 4;
   protected List<Cache> caches = new ArrayList<Cache>(NUM_NODES);
   protected static final String[] neededCacheNames = new String[] {
      org.infinispan.api.BasicCacheContainer.DEFAULT_CACHE_NAME,
      "LuceneIndexesMetadata",
      "LuceneIndexesData",
      "LuceneIndexesLocking",
   };

   private static final int DEFAULT_CACHE = 0;
   private static final int LUCENE_METADATA = 1;
   private static final int LUCENE_DATA = 2;
   private static final int LUCENE_LOCKING = 3;

   @Override
   protected void createCacheManagers() throws Throwable {
      EmbeddedCacheManager cacheManager = null;
      for (int i = 0; i < NUM_NODES; i++) {
         cacheManager = TestCacheManagerFactory.fromXml("dynamic-indexing-distribution.xml");
         registerCacheManager(cacheManager);
         Cache cache = cacheManager.getCache();
         caches.add(cache);
      }
      waitForClusterToForm(neededCacheNames);
   }

   public void testDisableIndexing() throws Exception {
      final int NB_ITEM = 10000;

      AdvancedCache cacheWithIndexing = caches.get(0).getAdvancedCache();
      long startTime = System.currentTimeMillis();
      System.out.println("Start import - " + NB_ITEM + " items (with indexing)");
      for (int i = 0; i < NB_ITEM ; i++)
         cacheWithIndexing.put(key("F" + i + "NUM"), new Car("megane", "blue", 300 + i));
      System.out.println("Import done in:" + (System.currentTimeMillis() - startTime) + "ms.");
      cacheWithIndexing.clear();

      AdvancedCache cacheNoIndex = caches.get(0).getAdvancedCache().withFlags(Flag.SKIP_INDEXING);
      startTime = System.currentTimeMillis();
      System.out.println("Start import - " + NB_ITEM + " items (without indexing)");
      for (int i = 0; i < NB_ITEM ; i++)
         cacheNoIndex.put(key("F" + i + "NUM"), new Car("megane", "blue", 300 + i));
      System.out.println("Import done in:" + (System.currentTimeMillis() - startTime) + "ms.");
      verifyFindsCar(0, "megane");

      System.out.println("Start reindexing");
      startTime = System.currentTimeMillis();
      rebuildIndexes();
      System.out.println("Reindexing done in:" + (System.currentTimeMillis() - startTime) + "ms.");
      verifyFindsCar(NB_ITEM, "megane");
      cacheNoIndex.clear();
   }

//   @Ignored
//   public void testReindexing() throws Exception {
//      caches.get(DEFAULT_CACHE).put(key("F1NUM"), new Car("megane", "white", 300));
//      verifyFindsCar(1, "megane");
//      caches.get(LUCENE_METADATA).put(key("F2NUM"), new Car("megane", "blue", 300));
//      verifyFindsCar(2, "megane");
//      //add an entry without indexing it:
//      caches.get(LUCENE_METADATA).getAdvancedCache().withFlags(Flag.SKIP_INDEXING).put(key("F3NUM"), new Car("megane", "blue", 300));
//      verifyFindsCar(2, "megane");
//      //re-sync datacontainer with indexes:
//      rebuildIndexes();
//      verifyFindsCar(3, "megane");
//      //verify we cleanup old stale index values:
//      caches.get(LUCENE_LOCKING).getAdvancedCache().withFlags(Flag.SKIP_INDEXING).remove(key("F2NUM"));
//      verifyFindsCar(3, "megane");
//      //re-sync
//      rebuildIndexes();
//      verifyFindsCar(2, "megane");
//   }

   private Object key(String keyId) {
      //Used to verify remoting is fine with non serializable keys
      return new NonSerializableKeyType(keyId);
   }

   protected void rebuildIndexes() throws Exception {
      Cache cache = caches.get(DEFAULT_CACHE);
      SearchManager searchManager = Search.getSearchManager(cache);
      searchManager.getMassIndexer().start();
   }

   private void verifyFindsCar(int expectedCount, String carMake) {
      for (Cache cache: caches) {
         verifyFindsCar(cache, expectedCount, carMake);
      }
   }

   protected void verifyFindsCar(Cache cache, int expectedCount, String carMake) {
      SearchManager searchManager = Search.getSearchManager(cache);
      QueryBuilder carQueryBuilder = searchManager.buildQueryBuilderForClass(Car.class).get();
      Query fullTextQuery = carQueryBuilder.keyword().onField("make").matching(carMake).createQuery();
      CacheQuery cacheQuery = searchManager.getQuery(fullTextQuery, Car.class);
      Assert.assertEquals(expectedCount, cacheQuery.getResultSize());
   }
}
