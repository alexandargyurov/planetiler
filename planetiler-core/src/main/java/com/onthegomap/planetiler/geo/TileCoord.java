package com.onthegomap.planetiler.geo;

import static com.onthegomap.planetiler.config.PlanetilerConfig.MAX_MAXZOOM;

import com.onthegomap.planetiler.util.Hilbert;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import javax.annotation.concurrent.Immutable;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.geom.Envelope;

/**
 * The coordinate of a <a href="https://wiki.openstreetmap.org/wiki/Slippy_map_tilenames">slippy map tile</a>.
 * <p>
 * Tile coords are sorted by consecutive Z levels in ascending order: 0 coords for z=0, 4 coords for z=1, etc. The
 * default is TMS order: a level is sorted by x ascending, y descending to match the ordering of the MBTiles sqlite
 * index.
 * <p>
 *
 * @param encoded the tile ID encoded as a 32-bit integer
 * @param x       x coordinate of the tile where 0 is the western-most tile just to the east the international date line
 *                and 2^z-1 is the eastern-most tile
 * @param y       y coordinate of the tile where 0 is the northern-most tile and 2^z-1 is the southern-most tile
 * @param z       zoom level ({@code <= 15})
 */
@Immutable
public record TileCoord(long encoded, int x, int y, int z) implements Comparable<TileCoord> {

  private static final long[] ZOOM_START_INDEX = new long[MAX_MAXZOOM + 1];

  static {
    long idx = 0;
    for (int z = 0; z <= MAX_MAXZOOM; z++) {
      ZOOM_START_INDEX[z] = idx;
      long count = (1 << z) * (1 << z);
      if (Long.MAX_VALUE - idx < count) {
        throw new IllegalStateException("Too many zoom levels " + MAX_MAXZOOM);
      }
      idx += count;
    }
  }

  private static long startIndexForZoom(int z) {
    return ZOOM_START_INDEX[z];
  }

  private static int zoomForIndex(long idx) {
    for (int z = MAX_MAXZOOM; z >= 0; z--) {
      if (ZOOM_START_INDEX[z] <= idx) {
        return z;
      }
    }
    throw new IllegalArgumentException("Bad index: " + idx);
  }

  public TileCoord {
    assert z <= MAX_MAXZOOM;
  }

  public static TileCoord ofXYZ(int x, int y, int z) {
    return new TileCoord(encode(x, y, z), x, y, z);
  }

  public static TileCoord decode(long encoded) {
    int z = zoomForIndex(encoded);
    long xy = tmsPositionToXY(z, encoded - startIndexForZoom(z));
    return new TileCoord(encoded, (int) (xy >>> 32 & 0xFFFFFFFFL), (int) (xy & 0xFFFFFFFFL), z);
  }

  /** Decode an integer using Hilbert ordering on a zoom level back to TMS ordering. */
  public static TileCoord hilbertDecode(long encoded) {
    int z = TileCoord.zoomForIndex(encoded);
    long xy = Hilbert.hilbertPositionToXY(z, (int)(encoded - TileCoord.startIndexForZoom(z)));
    return TileCoord.ofXYZ(Hilbert.extractX(xy), Hilbert.extractY(xy), z);
  }

  /** Returns the tile containing a latitude/longitude coordinate at a given zoom level. */
  public static TileCoord aroundLngLat(double lng, double lat, int zoom) {
    double factor = 1 << zoom;
    double x = GeoUtils.getWorldX(lng) * factor;
    double y = GeoUtils.getWorldY(lat) * factor;
    return TileCoord.ofXYZ((int) Math.floor(x), (int) Math.floor(y), zoom);
  }

  public static long encode(int x, int y, int z) {
    return startIndexForZoom(z) + tmsXYToPosition(z, x, y);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    TileCoord tileCoord = (TileCoord) o;

    return encoded == tileCoord.encoded;
  }

  @Override
  public int hashCode() {
    return (int)encoded;
  }

  @Override
  public String toString() {
    return "{x=" + x + " y=" + y + " z=" + z + '}';
  }

  public double progressOnLevel(TileExtents extents) {
    // approximate percent complete within a bounding box by computing what % of the way through the columns we are
    var zoomBounds = extents.getForZoom(z);
    return 1d * (x - zoomBounds.minX()) / (zoomBounds.maxX() - zoomBounds.minX());
  }

  public double hilbertProgressOnLevel(TileExtents extents) {
    return 1d * Hilbert.hilbertXYToIndex(this.z, this.x, this.y) / (1 << 2 * this.z);
  }

  @Override
  public int compareTo(TileCoord o) {
    return Long.compare(encoded, o.encoded);
  }

  /** Returns the latitude/longitude of the northwest corner of this tile. */
  public Envelope getEnvelope() {
    double worldWidthAtZoom = Math.pow(2, z);
    return new Envelope(
      GeoUtils.getWorldLon(x / worldWidthAtZoom),
      GeoUtils.getWorldLon((x + 1) / worldWidthAtZoom),
      GeoUtils.getWorldLat((y + 1) / worldWidthAtZoom),
      GeoUtils.getWorldLat(y / worldWidthAtZoom)
    );
  }


  /** Returns a URL that displays the openstreetmap data for this tile. */
  public String getDebugUrl(String pattern) {
    Coordinate center = getEnvelope().centre();
    DecimalFormat format = new DecimalFormat("0.#####", DecimalFormatSymbols.getInstance(Locale.US));
    return pattern
      .replaceAll("\\{(lat|latitude)}", format.format(center.y))
      .replaceAll("\\{(lon|longitude)}", format.format(center.x))
      .replaceAll("\\{(z|zoom)}", z + ".5");
  }

  /** Returns the pixel coordinate on this tile of a given latitude/longitude (assuming 256x256 px tiles). */
  public Coordinate lngLatToTileCoords(double lng, double lat) {
    double factor = 1 << z;
    double x = GeoUtils.getWorldX(lng) * factor;
    double y = GeoUtils.getWorldY(lat) * factor;
    return new CoordinateXY((x - Math.floor(x)) * 256, (y - Math.floor(y)) * 256);
  }

  /** Return the equivalent tile index using Hilbert ordering on a single level instead of TMS. */
  public int hilbertEncoded() {
    return (int)startIndexForZoom(this.z) +
      Hilbert.hilbertXYToIndex(this.z, this.x, this.y);
  }

  public int encodedAsInt() {
    return (int) encoded;
  }

  public static long tmsPositionToXY(int z, long pos) {
    if (z == 0)
      return 0;
    int dim = 1 << z;
    long x = pos / dim;
    long y = dim - 1 - (pos % dim);
    return ((long) x << 32) | (int)y;
  }

  public static long tmsXYToPosition(int z, int x, int y) {
    long dim = 1L << z;
    return x * dim + (dim - 1 - y);
  }

  public TileCoord parent() {
    return ofXYZ(x / 2, y / 2, z - 1);
  }

  public Envelope bounds() {
    double worldWidthAtZoom = Math.pow(2, z);
    return new Envelope(
      GeoUtils.getWorldLon(x / worldWidthAtZoom),
      GeoUtils.getWorldLon((x + 1) / worldWidthAtZoom),
      GeoUtils.getWorldLat(y / worldWidthAtZoom),
      GeoUtils.getWorldLat((y + 1) / worldWidthAtZoom)
    );
  }
}
