package com.onthegomap.planetiler.examples;

import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.Planetiler;
import com.onthegomap.planetiler.Profile;
import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.config.Arguments;
import com.onthegomap.planetiler.geo.GeometryException;
import com.onthegomap.planetiler.reader.SourceFeature;
import com.onthegomap.planetiler.reader.osm.OsmElement;
import com.onthegomap.planetiler.reader.osm.OsmRelationInfo;
import com.onthegomap.planetiler.reader.osm.OsmSourceFeature;
import com.onthegomap.planetiler.util.MemoryEstimator;
import org.locationtech.jts.algorithm.MinimumAreaRectangle;
import org.locationtech.jts.algorithm.construct.MaximumInscribedCircle;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

import static com.onthegomap.planetiler.util.MemoryEstimator.CLASS_HEADER_BYTES;

public class ShadowmapProfile implements Profile {
  @Override
  public String name() {
    return "Shadowmap Profile";
  }

  private static void processPoint(SourceFeature sourceFeature, FeatureCollector features) {
    if (sourceFeature.hasTag("natural", "tree")) {
      var feature = features.point("point")
        .setAttr("type", "tree")
        .setAttr("leafType", StreetsUtils.getLeafType(sourceFeature))
        .setAttr("genus", StreetsUtils.getGenus(sourceFeature))
        .setAttr("height", StreetsUtils.getTreeHeight(sourceFeature))
        .setAttr("minHeight", StreetsUtils.getMinHeight(sourceFeature));

      setCommonFeatureParams(feature, sourceFeature);
      return;
    }
  }

  private static void processLine(SourceFeature sourceFeature, FeatureCollector features) {

  }

  private void setPolygonOMBB(FeatureCollector.Feature feature) {
    Geometry geometry = feature.getGeometry();
    Geometry ombb = MinimumAreaRectangle.getMinimumRectangle(geometry);

    var coords = ombb.getCoordinates();

    if (coords.length != 5) {
      return;
    }

    feature.setAttr("@ombb00", coords[0].x);
    feature.setAttr("@ombb01", coords[0].y);

    feature.setAttr("@ombb10", coords[1].x);
    feature.setAttr("@ombb11", coords[1].y);

    feature.setAttr("@ombb20", coords[2].x);
    feature.setAttr("@ombb21", coords[2].y);

    feature.setAttr("@ombb30", coords[3].x);
    feature.setAttr("@ombb31", coords[3].y);
  }

  private void setPolygonPoleOfInaccessibility(FeatureCollector.Feature feature) {
    Geometry geometry = feature.getGeometry();
    LineString poi = MaximumInscribedCircle.getRadiusLine(geometry, 1e-9);

    var coords = poi.getCoordinates();

    feature.setAttr("@poiX", coords[0].x);
    feature.setAttr("@poiY", coords[0].y);
    feature.setAttr("@poiR", poi.getLength());
  }

  private boolean processArea(SourceFeature sourceFeature, FeatureCollector features) {
    if (
      (
        sourceFeature.hasTag("building:part") &&
          !sourceFeature.getTag("building:part").equals("no")
      ) || (
        sourceFeature.hasTag("building") &&
          !sourceFeature.getTag("building").equals("no") &&
          !this.isBuildingOutline(sourceFeature)
      )
    ) {
      Boolean isPart = sourceFeature.hasTag("building:part");
      String buildingType = isPart ? (String) sourceFeature.getTag("building:part") : (String) sourceFeature.getTag("building");

      var feature = features.polygon("buildings")
        .setAttr("type", "building")
        .setAttr("isPart", isPart)
        .setAttr("isML", false)
        .setAttr("buildingType", buildingType)
        .setAttr("height", StreetsUtils.getHeight(sourceFeature))
        .setAttr("minHeight", StreetsUtils.getMinHeight(sourceFeature))
        .setAttr("levels", StreetsUtils.getBuildingLevels(sourceFeature))
        .setAttr("minLevel", StreetsUtils.getBuildingMinLevel(sourceFeature))
        .setAttr("roofHeight", StreetsUtils.getRoofHeight(sourceFeature))
        .setAttr("roofLevels", StreetsUtils.getRoofLevels(sourceFeature))
        .setAttr("roofType", StreetsUtils.getRoofShape(sourceFeature))
        .setAttr("roofOrientation", StreetsUtils.getRoofOrientation(sourceFeature))
        .setAttr("roofDirection", StreetsUtils.getRoofDirection(sourceFeature))
        .setAttr("roofAngle", StreetsUtils.getAngle(sourceFeature));

      setPolygonOMBB(feature);
      setCommonFeatureParams(feature, sourceFeature);

      feature.setBufferPixels(isPart ? 128 : 64);

      return true;
    }

    return false;
  }

  private boolean processMLArea(SourceFeature sourceFeature, FeatureCollector features) {
    var feature = features.polygon("buildings")
      .setAttr("type", "building")
      .setAttr("isPart", false)
      .setAttr("isML", true)
      .setAttr("height", sourceFeature.getTag("height"))
      .setAttr("confidence", sourceFeature.getTag("confidence"))
      .setAttr("fid", sourceFeature.getTag("fid"));

    setPolygonOMBB(feature);
    setCommonFeatureParams(feature, sourceFeature);

    feature.setBufferPixels(64);

    return true;
  }

  @Override
  public void processFeature(SourceFeature sourceFeature, FeatureCollector features) {
    if (sourceFeature.getSource().equals("ms-ml")) {
      if (sourceFeature.canBePolygon()) {
        processMLArea(sourceFeature, features);
      }

      return;
    }

    if (StreetsUtils.isUnderground(sourceFeature)) {
      return;
    }

    if (sourceFeature.canBePolygon()) {
      boolean wasProcessed = processArea(sourceFeature, features);

      if (wasProcessed) {
        return;
      }
    }

    if (sourceFeature.canBeLine()) {
      processLine(sourceFeature, features);
      return;
    }

    if (sourceFeature.isPoint()) {
      processPoint(sourceFeature, features);
    }
  }

  private static void setCommonFeatureParams(FeatureCollector.Feature feature, SourceFeature sourceFeature) {
    if (sourceFeature instanceof OsmSourceFeature osmFeature) {
      OsmElement element = osmFeature.originalElement();

      feature
        .setAttr("osmId", sourceFeature.id())
        .setAttr("osmType", element instanceof OsmElement.Node ? 0 :
          element instanceof OsmElement.Way ? 1 :
            element instanceof OsmElement.Relation ? 2 : null
        );
    }

    feature
      .setZoomRange(16, 16)
      .setZoomLevels(Arrays.asList(16))
      .setPixelToleranceAtAllZooms(0)
      .setMinPixelSize(0)
      .setMinPixelSizeAtMaxZoom(0)
      .setBufferPixels(4);
  }

  public static void main(String[] args) throws Exception {
    run(Arguments.fromArgsOrConfigFile(args));
  }

  public static void run(Arguments args) throws Exception {
    Planetiler.create(args)
      .setProfile(new ShadowmapProfile())
      .addOsmSource("osm", Path.of("data", "sources", "litschau.osm.pbf"))
      //.addGeoPackageSource("ms-ml", Path.of("data", "sources", "ml-austria-2.gpkg"), null)
      .overwriteOutput(Path.of("data", "data.pmtiles"))
      .run();
  }

  @Override
  public void finish(String name, FeatureCollector.Factory featureCollectors, Consumer<FeatureCollector.Feature> next) {
    System.out.println("Finished");
  }

  private double convertAreaToMeters(double area, int zoom) {
    final double tileSizeInMeters = 40075016.68 / Math.pow(2, zoom);
    final double areaNormalizationFactor = (double) 1 / (256 * 256);

    return area * areaNormalizationFactor * tileSizeInMeters * tileSizeInMeters;
  }

  @Override
  public List<VectorTile.Feature> postProcessLayerFeatures(String layer, int zoom, List<VectorTile.Feature> items) throws GeometryException {
    final double maxMLIntersectionArea = 4; // m^2

    if (layer.equals("buildings")) {
      boolean hasParts = false;
      boolean hasML = false;

      for (VectorTile.Feature item : items) {
        if ((boolean) item.tags().get("isPart")) {
          hasParts = true;
          break;
        }
      }

      for (VectorTile.Feature item : items) {
        if ((boolean) item.tags().get("isML")) {
          hasML = true;
          break;
        }
      }

      if (hasParts || hasML) {
        var parts = new ArrayList<BuildingPartWithEnvelope>();
        var outlines = new ArrayList<BuildingOutlineWithEnvelope>();

        if (hasParts) {
          for (VectorTile.Feature item : items) {
            boolean isML = (boolean) item.tags().get("isML");

            if (isML) {
              continue;
            }

            boolean isPart = (boolean) item.tags().get("isPart");
            Geometry geometry = item.geometry().decode();
            Envelope bbox = geometry.getEnvelopeInternal();

            if (isPart) {
              parts.add(new BuildingPartWithEnvelope(item, geometry, bbox));
            } else {
              outlines.add(new BuildingOutlineWithEnvelope(item, geometry, bbox));
            }
          }

          for (BuildingOutlineWithEnvelope outline : outlines) {
            for (BuildingPartWithEnvelope part : parts) {
              if (!outline.envelope.intersects(part.envelope)) {
                continue;
              }

              try {
                outline.geometryCopy = outline.geometryCopy.difference(part.geometry);
              } catch (Exception ignored) {
              }
            }

            var initialOutlineArea = outline.geometry.getArea();
            var newOutlineArea = outline.geometryCopy.getArea();

            if (newOutlineArea / initialOutlineArea < 0.1) {
              items.remove(outline.feature);
            }
          }

          for (Iterator<BuildingPartWithEnvelope> it = parts.iterator(); it.hasNext(); ) {
            BuildingPartWithEnvelope part = it.next();

            if (!part.envelope.intersects(BuildingPartWithEnvelope.TileBoundsEnvelope)) {
              items.remove(part.feature);
              it.remove();
            }
          }
        }

        if (hasML) {
          var mlBuildings = new ArrayList<MLBuildingWithEnvelope>();

          for (VectorTile.Feature item : items) {
            boolean isML = (boolean) item.tags().get("isML");

            if (!isML) {
              continue;
            }

            Geometry geometry = item.geometry().decode();
            Envelope bbox = geometry.getEnvelopeInternal();

            mlBuildings.add(new MLBuildingWithEnvelope(item, geometry, bbox));
          }

          for (MLBuildingWithEnvelope mlBuilding : mlBuildings) {
            boolean isDuplicate = false;

            for (BuildingOutlineWithEnvelope outline : outlines) {
              if (!mlBuilding.envelope.intersects(outline.envelope)) {
                continue;
              }

              double intersectionArea = 0;

              try {
                intersectionArea = convertAreaToMeters(mlBuilding.geometry.intersection(outline.geometry).getArea(), zoom);
              } catch (Exception ignored) {
              }

              if (intersectionArea <= maxMLIntersectionArea) {
                continue;
              }

              isDuplicate = true;
              var mlHeight = (double) mlBuilding.feature.tags().get("height");

              outline.feature.tags().put("heightML", mlHeight);
            }

            for (BuildingPartWithEnvelope part : parts) {
              if (!mlBuilding.envelope.intersects(part.envelope)) {
                continue;
              }

              double intersectionArea = 0;

              try {
                intersectionArea = convertAreaToMeters(mlBuilding.geometry.intersection(part.geometry).getArea(), zoom);
              } catch (Exception ignored) {
              }

              if (intersectionArea <= maxMLIntersectionArea) {
                continue;
              }

              isDuplicate = true;
              var mlHeight = (double) mlBuilding.feature.tags().get("height");

              part.feature.tags().put("heightML", mlHeight);
            }

            if (isDuplicate) {
              items.remove(mlBuilding.feature);
            }
          }
        }
      }

      for (VectorTile.Feature item : items) {
        item.tags().remove("isPart");
        //item.attrs().remove("isML");
      }

      for (Iterator<VectorTile.Feature> it = items.iterator(); it.hasNext(); ) {
        VectorTile.Feature item = it.next();

        Geometry geometry = item.geometry().decode();
        Envelope bbox = geometry.getEnvelopeInternal();

        if (!bbox.intersects(BuildingPartWithEnvelope.TileBoundsEnvelope)) {
          it.remove();
        }
      }
    }

    return items;
  }

  @Override
  public List<OsmRelationInfo> preprocessOsmRelation(OsmElement.Relation relation) {
    if (relation.hasTag("type", "building")) {
      return List.of(new BuildingRelationInfo(relation.id()));
    }

    return null;
  }

  private record BuildingRelationInfo(long id) implements OsmRelationInfo {
    @Override
    public long estimateMemoryUsageBytes() {
      return CLASS_HEADER_BYTES + MemoryEstimator.estimateSizeLong(id);
    }
  }

  private boolean isBuildingOutline(SourceFeature sourceFeature) {
    var relations = sourceFeature.relationInfo(BuildingRelationInfo.class);

    for (var relation : relations) {
      if ("outline".equals(relation.role())) {
        return true;
      }
    }

    return false;
  }

  private static class BuildingPartWithEnvelope {
    static final Envelope TileBoundsEnvelope = new Envelope(-4, 260, -4, 260);

    VectorTile.Feature feature;
    Geometry geometry;
    Envelope envelope;

    public BuildingPartWithEnvelope(VectorTile.Feature feature, Geometry geometry, Envelope envelope) {
      this.feature = feature;
      this.geometry = geometry;
      this.envelope = envelope;
    }
  }

  private static class BuildingOutlineWithEnvelope {
    VectorTile.Feature feature;
    Geometry geometry;
    Geometry geometryCopy;
    Envelope envelope;

    public BuildingOutlineWithEnvelope(VectorTile.Feature feature, Geometry geometry, Envelope envelope) {
      this.feature = feature;
      this.geometry = geometry;
      this.envelope = envelope;
      this.geometryCopy = geometry.copy();
    }
  }

  private static class MLBuildingWithEnvelope {
    VectorTile.Feature feature;
    Geometry geometry;
    Envelope envelope;

    public MLBuildingWithEnvelope(VectorTile.Feature feature, Geometry geometry, Envelope envelope) {
      this.feature = feature;
      this.geometry = geometry;
      this.envelope = envelope;
    }
  }
}
