/**
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * @author Arne Kepp, The Open Planning Project, Copyright 2008
 *  
 */
package org.geowebcache.layer.wms;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.RasterFormatException;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.media.jai.JAI;
import javax.media.jai.operator.CropDescriptor;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.geowebcache.GeoWebCacheException;
import org.geowebcache.layer.MetaTile;
import org.geowebcache.layer.SRS;
import org.geowebcache.service.Request;
import org.geowebcache.service.wms.WMSParameters;
import org.geowebcache.util.wms.BBOX;

public class WMSMetaTile extends MetaTile {
    private static Log log = LogFactory
            .getLog(org.geowebcache.layer.wms.WMSMetaTile.class);

    private BufferedImage img = null; // buffer for storing the metatile

    private RenderedImage[] tiles = null; // array with tiles (after cropping)

    private long expiration = WMSLayerProfile.CACHE_VALUE_UNSET;

    public boolean failed = false;

    /**
     * Used for requests by clients
     * 
     * @param profile
     * @param initGridPosition
     */
    protected WMSMetaTile(SRS srs, int[] gridBounds, int[] tileGridPosition,
            int metaX, int metaY) {
        super(srs, gridBounds, tileGridPosition, metaX, metaY);
    }

    /**
     * Requests a metatile from the backend, or consults multiple backends if
     * necessary, and saves the result in the internal buffer for future
     * processing.
     * 
     * @param profile
     *            the profile provides the general parameters for the request
     * @param imageMime
     *            the desired image format
     * @return
     */
    protected String doRequest(WMSLayerProfile profile, SRS srs, String mimeType)
            throws GeoWebCacheException {
        WMSParameters wmsparams = profile.getWMSParamTemplate();

        int srsIdx = profile.getSRSIndex(srs);
        // Fill in the blanks
        wmsparams.setMime(mimeType);
        wmsparams.setSrs(srs.toString());
        wmsparams.setWidth(super.metaX * profile.width);
        wmsparams.setHeight(metaY * profile.height);
        BBOX metaBbox = profile.gridCalc[srsIdx]
                .bboxFromGridBounds(metaTileGridBounds);
        metaBbox.adjustForGeoServer(srs);
        wmsparams.setBBOX(metaBbox);

        // Ask the WMS server, saves returned image into metaTile
        // TODO add exception for configurations that do not use metatiling
        // TODO move this closer to the WMSLayer?
        String backendURL = "";
        int backendTries = 0; // keep track of how many backends we have tried
        while (img == null && backendTries < profile.wmsURL.length) {
            backendURL = profile.nextWmsURL();

            boolean saveExpiration = (profile.expireCache == WMSLayerProfile.CACHE_USE_WMS_BACKEND_VALUE || profile.expireClients == WMSLayerProfile.CACHE_USE_WMS_BACKEND_VALUE);

            try {
                forwardRequest(wmsparams, backendURL, saveExpiration);
            } catch (ConnectException ce) {
                log.error("Error forwarding request, " + backendURL
                        + wmsparams.toString() + " " + ce.getMessage());
            } catch (IOException ioe) {
                log.error("Error forwarding request, " + backendURL
                        + wmsparams.toString() + " " + ioe.getMessage());
                ioe.printStackTrace();
            }
            backendTries++;
        }

        if (img == null) {
            failed = true;
        }

        return backendURL + wmsparams.toString();
    }

    /**
     * Forwards the request to the actual WMS backend and saves the image that
     * comes back.
     * 
     * @param wmsparams
     *            the parameters for the request
     * @param backendURL
     *            the URL of the backend to use
     * @param saveExpiration
     *            whether to extract and save expiration headers
     * @throws IOException
     * @throws ConnectException
     */
    private void forwardRequest(WMSParameters wmsparams, String backendURL,
            boolean saveExpiration) throws IOException, ConnectException {
        // Create an outgoing WMS request to the server
        Request wmsrequest = new Request(backendURL, wmsparams);
        URL wmsBackendUrl = new URL(wmsrequest.toString());
        URLConnection wmsBackendCon = wmsBackendUrl.openConnection();

        // Do we need to keep track of expiration headers?
        if (saveExpiration) {
            String cacheControlHeader = wmsBackendCon
                    .getHeaderField("Cache-Control");
            Long wmsBackendMaxAge = extractHeaderMaxAge(cacheControlHeader);

            if (wmsBackendMaxAge != null) {
                log.info("Saved Cache-Control MaxAge from backend: "
                        + wmsBackendMaxAge.toString());
                expiration = wmsBackendMaxAge.longValue() * 1000;
            } else {
                log
                        .error("Layer profile wants MaxAge from backend,"
                                + " but backend does not provide this. Setting to 7200 seconds.");
                expiration = 7200 * 1000;
            }
        }

        img = ImageIO.read(wmsBackendCon.getInputStream());

        if (img == null) {
            // System.out.println("Failed fetching "+ wmsrequest.toString());
            log.error("Failed fetching: " + wmsrequest.toString());
        } else if (log.isDebugEnabled()) {
            // System.out.println("Fetched "+ wmsrequest.toString());
            log.debug("Requested and got: " + wmsrequest.toString());
        }

        if (log.isTraceEnabled()) {
            log.trace("Got image from backend, height: " + img.getHeight());
        }
    }

    /**
     * Cuts the metaTile into the specified number of tiles, the actual number
     * of tiles is determined by metaX and metaY, not the width and height
     * provided here.
     * 
     * @param tileWidth
     *            width of each tile
     * @param tileHeight
     *            height of each tile
     */
    protected void createTiles(int tileWidth, int tileHeight) {
        tiles = new RenderedImage[metaX * metaY];

        if (tiles.length > 1) {
            final RenderingHints no_cache = new RenderingHints(
                    JAI.KEY_TILE_CACHE, null);

            for (int y = 0; y < metaY; y++) {
                for (int x = 0; x < metaX; x++) {
                    int i = x * tileWidth;
                    int j = (metaY - 1 - y) * tileHeight;

                    try {
                        RenderedImage tile = CropDescriptor.create(img,
                                new Float(i), new Float(j),
                                new Float(tileWidth), new Float(tileHeight),
                                null);

                        tiles[y * metaX + x] = tile;

                        if (log.isDebugEnabled()) {
                            log.debug("Thread: "
                                    + Thread.currentThread().getName()
                                    + " rendering  index " + (y * metaX + x) + "\n"
                                    + tile.toString() + ", "
                                    + "Information from tile (width, height, minx, miny): "
                                    + tile.getWidth() + ", "
                                    + tile.getHeight() + ", "
                                    + tile.getMinX() + ", "
                                    + tile.getMinY() + "\n"
                                    + "Information set (width, height, minx, miny): "
                                    + new Float(tileWidth) + ", "
                                    + new Float(tileHeight) + ", "
                                    + new Float(i) + ", " 
                                    + new Float(j));
                        }

                    } catch (RasterFormatException rfe) {
                        log.error("Unable to get i: " + i + ", j:" + j);
                        rfe.printStackTrace();
                    }
                }
            }
        } else {
            tiles[0] = img;
        }
    }

    /**
     * Outputs one tile from the internal array of tiles to a provided stream
     * 
     * @param tileIdx
     *            the index of the tile relative to the internal array
     * @param format
     *            the Java name for the format
     * @param os
     *            the outputstream
     * @return true if no error was encountered
     * @throws IOException
     */
    protected boolean writeTileToStream(int tileIdx, String format,
            OutputStream os) throws IOException {
        if (tiles == null) {
            return false;
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Thread: " + Thread.currentThread().getName()
                        + " writing: " + tileIdx);
            }

            if (!javax.imageio.ImageIO.write(tiles[tileIdx], format, os)) {
                log.error("javax.imageio.ImageIO.write("
                        + tiles[tileIdx].toString() + "," + format + ","
                        + os.toString() + ")");
            }
            return true;
        }
    }

    private static Long extractHeaderMaxAge(String cacheControlHeader) {
        if (cacheControlHeader == null) {
            return null;
        }

        String expression = "max-age=([0-9]*)[ ,]";
        Pattern p = Pattern.compile(expression);
        Matcher m = p.matcher(cacheControlHeader.toLowerCase());

        if (m.find()) {
            return new Long(Long.parseLong(m.group(1)));
        } else {
            return null;
        }
    }

    protected BufferedImage getRawImage() {
        return img;
    }

    protected long getExpiration() {
        return expiration;
    }

    public String debugString() {
        return " metaX: " + metaX + " metaY: " + metaY + " metaGrid: "
                + Arrays.toString(metaTileGridBounds);
    }
}