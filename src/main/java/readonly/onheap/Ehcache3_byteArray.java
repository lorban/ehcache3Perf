/*
 * Copyright Terracotta, Inc.
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
package readonly.onheap;

import io.rainfall.Runner;
import io.rainfall.Scenario;
import io.rainfall.configuration.ConcurrencyConfig;
import io.rainfall.ehcache.statistics.EhcacheResult;
import io.rainfall.ehcache3.CacheConfig;
import io.rainfall.ehcache3.Ehcache3Operations;
import io.rainfall.generator.ByteArrayGenerator;
import io.rainfall.generator.LongGenerator;
import io.rainfall.generator.sequence.Distribution;
import io.rainfall.unit.TimeDivision;
import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.core.spi.service.StatisticsService;
import org.ehcache.impl.config.serializer.DefaultSerializationProviderConfiguration;
import org.ehcache.impl.internal.statistics.DefaultStatisticsService;
import utils.ByteArraySerializer;

import java.io.File;
import java.util.Timer;
import java.util.TimerTask;

import static io.rainfall.configuration.ReportingConfig.html;
import static io.rainfall.configuration.ReportingConfig.report;
import static io.rainfall.execution.Executions.during;
import static io.rainfall.execution.Executions.times;
import static org.ehcache.config.builders.ResourcePoolsBuilder.heap;

/**
 * analyze churn with $JAVA_HOME/bin/jmc
 * @author Ludovic Orban
 */
public class Ehcache3_byteArray {

  public static void main(String[] args) throws Exception {
    final int nbElementsPerThread = 100000;
    final StatisticsService statisticsService = new DefaultStatisticsService();
    CacheManager cacheManager = CacheManagerBuilder.newCacheManagerBuilder()
        .using(statisticsService)
        .using(new DefaultSerializationProviderConfiguration()
            .addSerializerFor(byte[].class, (Class) ByteArraySerializer.class)
        )
        .withCache("cache1", CacheConfigurationBuilder.newCacheConfigurationBuilder(Long.class, byte[].class, heap(nbElementsPerThread))
//            .withKeySerializingCopier().withValueSerializingCopier()
            .build())
        .build(true);

    final Cache<Long, byte[]> cache1 = cacheManager.getCache("cache1", Long.class, byte[].class);

    LongGenerator keyGenerator = new LongGenerator();
    ByteArrayGenerator valueGenerator = new ByteArrayGenerator(4096);

    CacheConfig<Long, byte[]> cacheConfig = new CacheConfig<Long, byte[]>();
    cacheConfig.cache("cache1", cache1);

    final File reportPath = new File("target/rainfall/" + Ehcache3_byteArray.class.getName().replace('.', '/'));
    Runner.setUp(
        Scenario.scenario("Loading phase")
            .exec(
                Ehcache3Operations.put(Long.class, byte[].class).using(keyGenerator, valueGenerator)
                    .sequentially()
            ))
        .executed(times(nbElementsPerThread))
        .config(
            ConcurrencyConfig.concurrencyConfig().threads(1),
            report(EhcacheResult.class),
            cacheConfig)
        .start();

    Timer t = new Timer(true);
    t.schedule(new TimerTask() {
      @Override
      public void run() {
        long hits = statisticsService.getCacheStatistics("cache1").getTierStatistics().get("OnHeap").getHits();
        System.out.println("             hits: " + hits);
      }
    }, 1000, 1000);

    System.out.println("testing...");

    Runner.setUp(
        Scenario.scenario("Testing phase")
            .exec(
                Ehcache3Operations.get(Long.class, byte[].class).using(keyGenerator, valueGenerator)
                    .atRandom(Distribution.GAUSSIAN, 0, nbElementsPerThread, nbElementsPerThread/10)
            ))
        .executed(during(120, TimeDivision.seconds))
        .config(
            ConcurrencyConfig.concurrencyConfig().threads(Runtime.getRuntime().availableProcessors()),
            report(EhcacheResult.class, new EhcacheResult[] {EhcacheResult.GET, EhcacheResult.MISS}).log(html(reportPath.getPath())),
            cacheConfig)
        .start();

    cacheManager.close();

    System.exit(0);
  }

}
