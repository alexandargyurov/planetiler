package com.onthegomap.flatmap.read;

import com.carrotsearch.hppc.LongHashSet;
import com.graphhopper.coll.GHLongHashSet;
import com.graphhopper.coll.GHLongObjectHashMap;
import com.graphhopper.reader.ReaderElement;
import com.graphhopper.reader.ReaderElementUtils;
import com.graphhopper.reader.ReaderNode;
import com.graphhopper.reader.ReaderRelation;
import com.graphhopper.reader.ReaderWay;
import com.onthegomap.flatmap.CommonParams;
import com.onthegomap.flatmap.FeatureCollector;
import com.onthegomap.flatmap.FeatureRenderer;
import com.onthegomap.flatmap.MemoryEstimator;
import com.onthegomap.flatmap.Profile;
import com.onthegomap.flatmap.SourceFeature;
import com.onthegomap.flatmap.collections.FeatureGroup;
import com.onthegomap.flatmap.collections.FeatureSort;
import com.onthegomap.flatmap.collections.LongLongMap;
import com.onthegomap.flatmap.collections.LongLongMultimap;
import com.onthegomap.flatmap.geo.GeoUtils;
import com.onthegomap.flatmap.monitoring.ProgressLoggers;
import com.onthegomap.flatmap.monitoring.Stats;
import com.onthegomap.flatmap.worker.Topology;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.locationtech.jts.geom.Geometry;

public class OpenStreetMapReader implements Closeable, MemoryEstimator.HasEstimate {

  private final OsmInputFile osmInputFile;
  private final Stats stats;
  private final LongLongMap nodeDb;
  private final AtomicLong TOTAL_NODES = new AtomicLong(0);
  private final AtomicLong TOTAL_WAYS = new AtomicLong(0);
  private final AtomicLong TOTAL_RELATIONS = new AtomicLong(0);
  private final Profile profile;

  // need a few large objects to process ways in relations, should be small enough to keep in memory
  // for routes (750k rels 40m ways) and boundaries (650k rels, 8m ways)
  // need to store route info to use later when processing ways
  // <~500mb
  private GHLongObjectHashMap<RelationInfo> relationInfo = new GHLongObjectHashMap<>();
  private final AtomicLong relationInfoSizes = new AtomicLong(0);
  // ~800mb, ~1.6GB when sorting
  private LongLongMultimap wayToRelations = LongLongMultimap.newSparseUnorderedMultimap();
  // for multipolygons need to store way info (20m ways, 800m nodes) to use when processing relations (4.5m)
  // ~300mb
  private LongHashSet waysInMultipolygon = new GHLongHashSet();
  // ~7GB
  private LongLongMultimap multipolygonWayGeometries = LongLongMultimap.newDensedOrderedMultimap();

  public OpenStreetMapReader(OsmInputFile osmInputFile, LongLongMap nodeDb, Profile profile, Stats stats) {
    this.osmInputFile = osmInputFile;
    this.nodeDb = nodeDb;
    this.stats = stats;
    this.profile = profile;
  }

  public void pass1(CommonParams config) {
    var topology = Topology.start("osm_pass1", stats)
      .fromGenerator("pbf", osmInputFile.read(config.threads() - 1))
      .addBuffer("reader_queue", 50_000, 10_000)
      .sinkToConsumer("process", 1, (readerElement) -> {
        if (readerElement instanceof ReaderNode node) {
          TOTAL_NODES.incrementAndGet();
          nodeDb.put(node.getId(), GeoUtils.encodeFlatLocation(node.getLon(), node.getLat()));
        } else if (readerElement instanceof ReaderWay) {
          TOTAL_WAYS.incrementAndGet();
        } else if (readerElement instanceof ReaderRelation rel) {
          TOTAL_RELATIONS.incrementAndGet();
          List<RelationInfo> infos = profile.preprocessOsmRelation(rel);
          if (infos != null) {
            for (RelationInfo info : infos) {
              relationInfo.put(rel.getId(), info);
              relationInfoSizes.addAndGet(info.estimateMemoryUsageBytes());
              for (ReaderRelation.Member member : rel.getMembers()) {
                if (member.getType() == ReaderRelation.Member.WAY) {
                  wayToRelations.put(member.getRef(), rel.getId());
                }
              }
            }
          }
          if (rel.hasTag("type", "multipolygon")) {
            for (ReaderRelation.Member member : rel.getMembers()) {
              if (member.getType() == ReaderRelation.Member.WAY) {
                waysInMultipolygon.add(member.getRef());
              }
            }
          }
        }
      });

    var loggers = new ProgressLoggers("osm_pass1")
      .addRateCounter("nodes", TOTAL_NODES)
      .addFileSize(nodeDb::fileSize)
      .addRateCounter("ways", TOTAL_WAYS)
      .addRateCounter("rels", TOTAL_RELATIONS)
      .addProcessStats()
      .addInMemoryObject("hppc", this)
      .addThreadPoolStats("parse", "pool-")
      .addTopologyStats(topology);
    topology.awaitAndLog(loggers, config.logInterval());
  }

  public void pass2(FeatureRenderer renderer, FeatureGroup writer, CommonParams config) {
    int readerThreads = Math.max(config.threads() / 4, 1);
    int processThreads = config.threads() - 1;
    AtomicLong nodesProcessed = new AtomicLong(0);
    AtomicLong waysProcessed = new AtomicLong(0);
    AtomicLong relsProcessed = new AtomicLong(0);
    CountDownLatch waysDone = new CountDownLatch(processThreads);

    var topology = Topology.start("osm_pass2", stats)
      .fromGenerator("pbf", osmInputFile.read(readerThreads))
      .addBuffer("reader_queue", 50_000, 1_000)
      .<FeatureSort.Entry>addWorker("process", processThreads, (prev, next) -> {
        ReaderElement readerElement;
        while ((readerElement = prev.get()) != null) {
          SourceFeature feature = null;
          if (readerElement instanceof ReaderNode node) {
            nodesProcessed.incrementAndGet();
            feature = new NodeSourceFeature(node);
          } else if (readerElement instanceof ReaderWay way) {
            waysProcessed.incrementAndGet();
            feature = new WaySourceFeature(way);
          } else if (readerElement instanceof ReaderRelation rel) {
            // ensure all ways finished processing before we start relations
            if (waysDone.getCount() > 0) {
              waysDone.countDown();
              waysDone.await();
            }
            relsProcessed.incrementAndGet();
            if (rel.hasTag("type", "multipolygon")) {
              feature = new MultipolygonSourceFeature(rel);
            }
          }
          if (feature != null) {
            FeatureCollector features = FeatureCollector.from(feature);
            profile.processFeature(feature, features);
            for (FeatureCollector.Feature renderable : features) {
              renderer.renderFeature(renderable, next);
            }
          }
        }

        // just in case a worker skipped over all relations
        waysDone.countDown();
      }).addBuffer("feature_queue", 50_000, 1_000)
      .sinkToConsumer("write", 1, writer);

    var logger = new ProgressLoggers("osm_pass2")
      .addRatePercentCounter("nodes", TOTAL_NODES.get(), nodesProcessed)
      .addFileSize(nodeDb::fileSize)
      .addRatePercentCounter("ways", TOTAL_WAYS.get(), waysProcessed)
      .addRatePercentCounter("rels", TOTAL_RELATIONS.get(), relsProcessed)
      .addRateCounter("features", () -> writer.sorter().size())
      .addFileSize(writer::getStorageSize)
      .addProcessStats()
      .addInMemoryObject("hppc", this)
      .addThreadPoolStats("parse", "pool-")
      .addTopologyStats(topology);

    topology.awaitAndLog(logger, config.logInterval());
  }

  @Override
  public long estimateMemoryUsageBytes() {
    long size = 0;
    size += MemoryEstimator.size(waysInMultipolygon);
    size += MemoryEstimator.size(multipolygonWayGeometries);
    size += MemoryEstimator.size(wayToRelations);
    size += MemoryEstimator.sizeWithoutValues(relationInfo);
    size += relationInfoSizes.get();
    return size;
  }


  @Override
  public void close() throws IOException {
    multipolygonWayGeometries = null;
    wayToRelations = null;
    waysInMultipolygon = null;
    relationInfo = null;
    nodeDb.close();
  }

  public static class RelationInfo implements MemoryEstimator.HasEstimate {

    @Override
    public long estimateMemoryUsageBytes() {
      return 0;
    }
  }

  private static abstract class ProxyFeature implements SourceFeature {

    protected final Map<String, Object> tags;

    public ProxyFeature(ReaderElement elem) {
      tags = ReaderElementUtils.getProperties(elem);
    }

    @Override
    public Map<String, Object> properties() {
      return null;
    }
  }

  private static class NodeSourceFeature extends ProxyFeature {

    public NodeSourceFeature(ReaderNode node) {
      super(node);
    }

    @Override
    public Geometry geometry() {
      return null;
    }
  }

  private static class WaySourceFeature extends ProxyFeature {

    public WaySourceFeature(ReaderWay way) {
      super(way);
    }

    @Override
    public Geometry geometry() {
      return null;
    }
  }

  private static class MultipolygonSourceFeature extends ProxyFeature {

    public MultipolygonSourceFeature(ReaderRelation relation) {
      super(relation);
    }

    @Override
    public Geometry geometry() {
      return null;
    }
  }
}
