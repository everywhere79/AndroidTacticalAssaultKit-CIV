
package com.atakmap.android.maps.graphics;

import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.opengl.GLES30;
import android.util.Pair;

import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapTextFormat;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Polyline;
import com.atakmap.android.maps.Shape.OnBasicLineStyleChangedListener;
import com.atakmap.android.maps.Polyline.OnLabelsChangedListener;
import com.atakmap.android.maps.Shape;
import com.atakmap.android.maps.hittest.PartitionRect;
import com.atakmap.android.maps.hittest.ShapeHitTestControl;
import com.atakmap.annotations.DeprecatedApi;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoCalculations;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.Vector2D;
import com.atakmap.lang.Unsafe;
import com.atakmap.map.Globe;
import com.atakmap.map.MapRenderer;
import com.atakmap.map.MapRenderer2;
import com.atakmap.map.layer.feature.Feature.AltitudeMode;
import com.atakmap.map.layer.feature.geometry.Envelope;
import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.geometry.Polygon;
import com.atakmap.map.layer.feature.geometry.opengl.GLBatchLineString;
import com.atakmap.map.layer.feature.geometry.opengl.GLBatchPolygon;
import com.atakmap.map.layer.feature.geometry.opengl.GLExtrude;
import com.atakmap.map.layer.feature.style.BasicFillStyle;
import com.atakmap.map.layer.feature.style.BasicStrokeStyle;
import com.atakmap.map.layer.feature.style.CompositeStyle;
import com.atakmap.map.layer.feature.style.PatternStrokeStyle;
import com.atakmap.map.layer.feature.style.Style;
import com.atakmap.map.opengl.GLAntiMeridianHelper;
import com.atakmap.map.opengl.GLMapView;
import com.atakmap.map.opengl.GLRenderGlobals;
import com.atakmap.math.MathUtils;
import com.atakmap.math.Matrix;
import com.atakmap.math.PointD;
import com.atakmap.math.Rectangle;
import com.atakmap.opengl.GLES20FixedPipeline;
import com.atakmap.opengl.GLNinePatch;
import com.atakmap.opengl.GLText;

import org.apache.commons.lang.StringUtils;

import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class GLPolyline extends GLShape2 implements
        Shape.OnPointsChangedListener, OnBasicLineStyleChangedListener,
        OnLabelsChangedListener, Polyline.OnLabelTextSizeChanged,
        Polyline.OnAltitudeModeChangedListener,
        Polyline.OnHeightStyleChangedListener,
        Shape.OnHeightChangedListener,
        ShapeHitTestControl {

    public static final String TAG = "GLPolyline";

    private final static double DEFAULT_MIN_RENDER_SCALE = (1.0d / 100000.0d);

    private static final double threshold = 160000;

    private final Polyline _subject;
    private boolean _closed;
    private DoubleBuffer _points;
    protected int numPoints;
    protected int _pointsSize = 2;
    private GeoPoint[] origPoints;
    protected boolean _needsUpdate;
    protected FloatBuffer _verts2;
    /** XY = 2, XYZ = 3; subclasses may set in constructor */
    protected int _verts2Size = 2;
    protected long _verts2Ptr;
    private boolean _outlineStroke, _outlineHalo;

    protected int basicLineStyle;
    private int _labelTextSize;
    private Typeface _labelTypeface;
    private static final float div_2 = 1f / 2f;
    protected static final double div_180_pi = 180d / Math.PI;
    private float _textAngle;
    private final float[] _textPoint = new float[2];
    private GLText _label;

    // Line label
    private String _lineLabel;
    private String[] _lineLabelArr;
    private float _lineLabelWidth, _lineLabelHeight;

    private GeoPoint centerPoint;
    private GLText _cplabel;
    protected long currentDraw = 0;
    private int labelsVersion = -1;
    protected boolean recompute = true;

    private Map<String, Object> segmentLabels = null;

    private String centerLabelText = null;
    private int middle = -1;

    public AltitudeMode altitudeMode;

    private final GLBatchLineString impl;

    protected boolean needsProjectVertices;

    // Height extrusion
    /** extrusion vertices, LLA */
    private DoubleBuffer _3dPointsPreForward;
    /** extrusion vertices, map projection; relative-to-center */
    private FloatBuffer _3dPoints;
    /** extrused outline vertices, LLA */
    private DoubleBuffer _outlinePointsPreForward;
    /** extruded outline vertices, map projection; relative-to-center */
    private FloatBuffer _outlinePoints;
    private boolean _shouldReextrude;
    private double _height = Double.NaN;
    private boolean _hasHeight;
    private int _heightStyle;
    private int _extrudeMode;
    private boolean _extrudeCrossesIDL;
    private int _extrudePrimaryHemi;
    private GeoPoint _extrusionCentroid = GeoPoint.createMutable();
    private PointD _extrusionCentroidProj = new PointD(0d, 0d, 0d);
    private int _extrusionCentroidSrid = -1;
    private int _extrusionTerrainVersion = -1;

    // Hit testing
    protected RectF _screenRect = new RectF();
    protected List<PartitionRect> _partitionRects = new ArrayList<>();

    static class SegmentLabel {
        public String[] text;
        float text_r = 1f;
        float text_g = 1f;
        float text_b = 1f;
        float text_a = 1f;
        GeoPoint lla;
        float textOffx;
        float textOffy;
        float textWidth;
        float textHeight;
        float textAngle;
        GLText renderer;
        GLNinePatch patch;
        float patch_r = 0f;
        float patch_g = 0f;
        float patch_b = 0f;
        float patch_a = 0.8f;
        float patchOffx;
        float patchOffy;
        float patchWidth;
        float patchHeight;
    }

    private List<SegmentLabel> _labels = new ArrayList<>();

    public GLPolyline(MapRenderer surface, Polyline subject) {
        super(surface, subject, GLMapView.RENDER_PASS_SPRITES
                | GLMapView.RENDER_PASS_SURFACE
                | GLMapView.RENDER_PASS_SCENES);
        this.impl = new GLBatchPolygon(surface);
        this.impl.setTesselationThreshold(threshold);

        _subject = subject;
        GeoPoint[] points = subject.getPoints();
        centerPoint = subject.getCenter().get();
        this.updatePointsImpl(centerPoint, points);

        basicLineStyle = subject.getBasicLineStyle();
        _heightStyle = subject.getHeightStyle();
        super.onStyleChanged(_subject);
        super.onFillColorChanged(_subject);
        super.onStrokeColorChanged(_subject);
        super.onStrokeWeightChanged(_subject);
        refreshStyle();
        segmentLabels = subject.getLabels();
        _labelTextSize = subject.getLabelTextSize();
        _labelTypeface = subject.getLabelTypeface();
        onAltitudeModeChanged(subject.getAltitudeMode());
        // synchronized(subject) {
        // unwrapLng = subject.getMetaBoolean("unwrapLongitude", false);
        // }

        this.needsProjectVertices = (this.getClass() != GLPolyline.class);
        onHeightChanged(_subject);
    }

    @Override
    public void startObserving() {
        super.startObserving();
        onPointsChanged(_subject);
        onLabelsChanged(_subject);
        altitudeMode = _subject.getAltitudeMode();
        refreshStyle();
        _subject.addOnPointsChangedListener(this);
        _subject.addOnBasicLineStyleChangedListener(this);
        _subject.addOnLabelsChangedListener(this);
        _subject.addOnLabelTextSizeChangedListener(this);
        _subject.addOnAltitudeModeChangedListener(this);
        _subject.addOnHeightChangedListener(this);
        _subject.addOnHeightStyleChangedListener(this);
    }

    @Override
    public void stopObserving() {
        super.stopObserving();
        _subject.removeOnPointsChangedListener(this);
        _subject.removeOnBasicLineStyleChangedListener(this);
        _subject.removeOnLabelsChangedListener(this);
        _subject.removeOnLabelTextSizeChangedListner(this);
        _subject.removeOnAltitudeModeChangedListener(this);
        _subject.removeOnHeightChangedListener(this);
        _subject.removeOnHeightStyleChangedListener(this);
    }

    @Override
    public void onAltitudeModeChanged(final AltitudeMode altitudeMode) {
        context.queueEvent(new Runnable() {
            @Override
            public void run() {
                if (altitudeMode != GLPolyline.this.altitudeMode) {
                    GLPolyline.this.altitudeMode = altitudeMode;
                    updatePointsImpl(centerPoint, origPoints);
                }
                impl.setAltitudeMode(altitudeMode);
            }
        });
    }

    @Override
    public void onStyleChanged(Shape shape) {
        super.onStyleChanged(shape);

        context.queueEvent(new Runnable() {
            @Override
            public void run() {
                refreshStyle();
            }
        });
    }

    @Override
    public void onFillColorChanged(Shape shape) {
        super.onFillColorChanged(shape);
        context.queueEvent(new Runnable() {
            @Override
            public void run() {
                refreshStyle();
            }
        });
    }

    @Override
    public void onStrokeColorChanged(Shape shape) {
        super.onStrokeColorChanged(shape);
        context.queueEvent(new Runnable() {
            @Override
            public void run() {
                refreshStyle();
            }
        });
    }

    @Override
    public void onStrokeWeightChanged(Shape shape) {
        super.onStrokeWeightChanged(shape);
        context.queueEvent(new Runnable() {
            @Override
            public void run() {
                refreshStyle();
            }
        });
    }

    private static int argb(float r, float g, float b, float a) {
        return Color.argb((int) (a * 255), (int) (r * 255), (int) (g * 255),
                (int) (b * 255));
    }

    private void refreshStyle() {
        final int style = _subject.getStyle();
        final int basicStyle = _subject.getBasicLineStyle();
        boolean fill = this.fill && (!_hasHeight || !MathUtils.hasBits(
                _heightStyle, Polyline.HEIGHT_STYLE_POLYGON));

        boolean closed = (style & Polyline.STYLE_CLOSED_MASK) != 0;
        if (closed != _closed) {
            _closed = closed;
            updatePointsImpl(this.centerPoint, this.origPoints);
        }

        _outlineStroke = ((style & Polyline.STYLE_OUTLINE_STROKE_MASK) != 0);
        _outlineHalo = ((style & Polyline.STYLE_OUTLINE_HALO_MASK) != 0);
        basicLineStyle = basicStyle;

        Style s;
        if (basicStyle == Polyline.BASIC_LINE_STYLE_DASHED)
            s = new PatternStrokeStyle(0x3F, 8, this.strokeColor,
                    this.strokeWeight);
        else if (basicStyle == Polyline.BASIC_LINE_STYLE_DOTTED)
            s = new PatternStrokeStyle(0x03, 8, this.strokeColor,
                    this.strokeWeight);
        else if (basicStyle == Polyline.BASIC_LINE_STYLE_OUTLINED) {
            BasicStrokeStyle bg = new BasicStrokeStyle(
                    0xFF000000 & this.strokeColor, this.strokeWeight + 2f);
            s = new CompositeStyle(new Style[] {
                    bg, new BasicStrokeStyle(this.strokeColor, this.strokeWeight)
            });
        } else {
            s = new BasicStrokeStyle(this.strokeColor, this.strokeWeight);
        }
        int numStyles = 0;
        if (fill)
            numStyles++;
        if (_outlineStroke)
            numStyles++;
        if (_outlineHalo)
            numStyles += 2;

        ArrayList<Style> composite = (numStyles > 0)
                ? new ArrayList<Style>(numStyles + 1)
                : null;

        if (composite != null) {
            if (fill) {
                composite.add(new BasicFillStyle(this.fillColor));
            }
            if (_outlineStroke) {
                composite
                        .add(new BasicStrokeStyle(argb(0f, 0f, 0f, strokeAlpha),
                                strokeWeight + 2f));
            }
            if (_outlineHalo) {
                composite.add(new BasicStrokeStyle(
                        argb(strokeRed, strokeGreen, strokeBlue,
                                strokeAlpha / 8f),
                        strokeWeight + 10f));
                composite.add(new BasicStrokeStyle(
                        argb(strokeRed, strokeGreen, strokeBlue,
                                strokeAlpha / 4f),
                        strokeWeight + 4f));
            }

            composite.add(s);
            s = new CompositeStyle(
                    composite.toArray(new Style[0]));
        }

        final Style fs = s;
        runOnGLThread(new Runnable() {
            @Override
            public void run() {
                impl.setStyle(fs);
                markSurfaceDirty(true);
            }
        });
    }

    @Override
    public void onPointsChanged(Shape polyline) {
        final GeoPoint center = polyline.getCenter().get();
        final GeoPoint[] points = polyline.getPoints();
        runOnGLThread(new Runnable() {
            @Override
            public void run() {
                GLPolyline.this.updatePointsImpl(center, points);
            }
        });
    }

    /**
     * @deprecated use {@link #updatePointsImpl(GeoPoint, GeoPoint[])}
     */
    @Deprecated
    @DeprecatedApi(since = "4.1", forRemoval = true, removeAt = "4.4")
    protected void updatePointsImpl(GeoPoint[] points) {
        final GeoPoint center = GeoCalculations.centerOfExtremes(points, 0,
                points.length);
        if (center != null) {
            updatePointsImpl(center, points);
        }
    }

    protected void updatePointsImpl(GeoPoint center, GeoPoint[] points) {

        if (points == null)
            points = new GeoPoint[0];
        _pointsSize = altitudeMode == AltitudeMode.ClampToGround
                && !_hasHeight ? 2 : 3;
        centerPoint = center;
        int pLen = points.length * _pointsSize;
        if (_points == null || _points.capacity() < pLen) {
            Unsafe.free(_points);
            _points = Unsafe.allocateDirect(pLen, DoubleBuffer.class);
        }

        final LineString ls = new LineString(3);

        _points.clear();
        for (GeoPoint gp : points) {
            _points.put(gp.getLongitude());
            _points.put(gp.getLatitude());
            if (_pointsSize == 3)
                _points.put(gp.getAltitude());

            ls.addPoint(gp.getLongitude(), gp.getLatitude(),
                    Double.isNaN(gp.getAltitude()) ? 0d : gp.getAltitude());
        }
        if (points.length > 0 && _closed) {
            ls.addPoint(points[0].getLongitude(), points[0].getLatitude(),
                    Double.isNaN(points[0].getAltitude()) ? 0d
                            : points[0].getAltitude());
        }

        _points.flip();
        this.origPoints = points;
        this.numPoints = points.length;

        _needsUpdate = true;

        // force a redraw
        currentDraw = 0;
        labelsVersion = 0;

        // Need to update the height extrusion
        if (_hasHeight)
            _shouldReextrude = true;

        // Update points and bounds on the GL thread
        runOnGLThread(new Runnable() {
            @Override
            public void run() {
                if (impl instanceof GLBatchPolygon)
                    ((GLBatchPolygon) impl).setGeometry(new Polygon(ls));
                else
                    impl.setGeometry(ls);

                MapView mv = MapView.getMapView();
                if (mv != null) {
                    Envelope env = impl.getBounds(mv.getProjection()
                            .getSpatialReferenceID());
                    if (env != null) {
                        bounds.setWrap180(mv.isContinuousScrollEnabled());
                        bounds.set(env.minY, env.minX, env.maxY, env.maxX);
                        // XXX - naive implementation, will need to handle IDL better
                        bounds.getCenter(_extrusionCentroid);

                        dispatchOnBoundsChanged();
                    }
                    _extrusionCentroidSrid = -1;
                }
            }
        });
    }

    @Override
    public void onBasicLineStyleChanged(Shape polyline) {
        final int style = polyline.getBasicLineStyle();
        runOnGLThread(new Runnable() {
            @Override
            public void run() {
                basicLineStyle = style;
                refreshStyle();
                recompute = true;
            }
        });
    }

    @Override
    public void onLabelsChanged(Polyline polyline) {
        final Map<String, Object> labels = polyline.getLabels();
        runOnGLThread(new Runnable() {
            @Override
            public void run() {
                // specifically for the center label logic
                centerLabelText = null;
                segmentLabels = labels;
                if (segmentLabels != null) {
                    Map<String, Object> labelBundle = null;
                    for (Map.Entry<String, Object> e : segmentLabels
                            .entrySet()) {
                        Object v = e.getValue();
                        if (v instanceof Map)
                            labelBundle = (Map<String, Object>) e.getValue();
                    }
                    if (labelBundle != null)
                        centerLabelText = (String) labelBundle.get("text");
                }
                labelsVersion = -1;
            }
        });
    }

    @Override
    public void onHeightChanged(MapItem item) {
        refreshHeight();
    }

    @Override
    public void onHeightStyleChanged(Polyline p) {
        refreshHeight();
    }

    private void refreshHeight() {
        final double height = _subject.getHeight();
        final int heightStyle = _subject.getHeightStyle();
        final int extrudeMode = _subject.getHeightExtrudeMode();
        final boolean hasHeight = !Double.isNaN(height)
                && Double.compare(height, 0) != 0
                && heightStyle != Polyline.HEIGHT_STYLE_NONE;
        context.queueEvent(new Runnable() {
            @Override
            public void run() {
                setHeightEnabled(hasHeight);
                _height = height;
                _extrudeMode = extrudeMode;
                if (_heightStyle != heightStyle) {
                    _heightStyle = heightStyle;
                    if (_hasHeight)
                        refreshStyle();
                }
                if (_hasHeight)
                    _shouldReextrude = true;
            }
        });
    }

    private void setHeightEnabled(boolean hasHeight) {
        if (_hasHeight != hasHeight) {
            _hasHeight = hasHeight;

            // Refresh fill style so we only show the terrain fill
            // when the height extrusion hasn't already taken care of it
            refreshStyle();

            // Free unused buffers
            if (!_hasHeight) {
                Unsafe.free(_3dPoints);
                _3dPoints = null;

                Unsafe.free(_3dPointsPreForward);
                _3dPointsPreForward = null;

                Unsafe.free(_outlinePoints);
                _outlinePoints = null;

                Unsafe.free(_outlinePointsPreForward);
                _outlinePointsPreForward = null;
            }
        }
    }

    /**
     * Get the current extrude mode
     * @return Current extrude mode
     */
    private int getExtrudeMode() {
        int extrudeMode = _extrudeMode;

        // Extrude mode based on shape properties
        if (extrudeMode == Polyline.HEIGHT_EXTRUDE_DEFAULT) {
            // By default closed shapes use "building style" extrusion
            // where the top/bottom of the polygon is flat
            // Open shapes use per-point extrusion like a fence or wall
            if (_closed)
                extrudeMode = Polyline.HEIGHT_EXTRUDE_CENTER_ALT;
            else
                extrudeMode = Polyline.HEIGHT_EXTRUDE_PER_POINT;
        }

        return extrudeMode;
    }

    @Override
    public void draw(GLMapView ortho, int renderPass) {
        if ((renderPass & this.renderPass) == 0)
            return;

        boolean sprites = MathUtils.hasBits(renderPass,
                GLMapView.RENDER_PASS_SPRITES);
        boolean surface = MathUtils.hasBits(renderPass,
                GLMapView.RENDER_PASS_SURFACE);
        boolean scenes = MathUtils.hasBits(renderPass,
                GLMapView.RENDER_PASS_SCENES);

        // Not in resolution to draw
        if (ortho.drawMapResolution > _subject.getMetaDouble(
                "maxLineRenderResolution",
                Polyline.DEFAULT_MAX_LINE_RENDER_RESOLUTION))
            return;

        else if (ortho.drawMapResolution < _subject.getMetaDouble(
                "minLineRenderResolution",
                Polyline.DEFAULT_MIN_LINE_RENDER_RESOLUTION))
            return;

        if (_hasHeight && scenes && numPoints > 0) {

            boolean renderPolygon = MathUtils.hasBits(_heightStyle,
                    Polyline.HEIGHT_STYLE_POLYGON);
            boolean simpleOutline = MathUtils.hasBits(_heightStyle,
                    Polyline.HEIGHT_STYLE_OUTLINE_SIMPLE);
            boolean renderOutline = MathUtils.hasBits(_heightStyle,
                    Polyline.HEIGHT_STYLE_OUTLINE) || simpleOutline;

            // if terrain is modified
            final int terrainVersion = ortho.getTerrainVersion();
            _shouldReextrude |= (_extrusionTerrainVersion != terrainVersion);
            if (_shouldReextrude) {
                _extrusionTerrainVersion = terrainVersion;
                updatePointsImpl(centerPoint, origPoints);

                // Find min/max altitude
                boolean clampToGround = altitudeMode == AltitudeMode.ClampToGround;
                double minAlt = Double.MAX_VALUE;
                double maxAlt = -Double.MAX_VALUE;
                double[] alts = new double[origPoints.length];
                for (int i = 0; i < origPoints.length; i++) {
                    GeoPoint gp = origPoints[i];
                    double alt = gp.getAltitude();
                    if (clampToGround || !gp.isAltitudeValid())
                        alt = ortho.getTerrainMeshElevation(gp.getLatitude(),
                                gp.getLongitude());
                    minAlt = Math.min(alt, minAlt);
                    maxAlt = Math.max(alt, maxAlt);
                    alts[i] = alt;
                }

                // Center altitude is meant to be (min + max) / 2 based on
                // how KMLs render relative height
                double centerAlt = (maxAlt + minAlt) / 2;

                int extrudeMode = getExtrudeMode();
                double height = _height;
                double baseAltitude = minAlt;

                if (extrudeMode == Polyline.HEIGHT_EXTRUDE_MAX_ALT)
                    baseAltitude = maxAlt;
                else if (extrudeMode == Polyline.HEIGHT_EXTRUDE_CENTER_ALT)
                    baseAltitude = centerAlt; // KML style

                // Update point buffer with terrain elevations if we're clamped
                if (clampToGround) {
                    // XXX - Dirty hack for ATAK-14494
                    // Use the lowest valid altitude value as the base of the
                    // extrusion
                    if (ortho.currentPass.drawTilt > 0) {
                        Arrays.fill(alts, GeoPoint.MIN_ACCEPTABLE_ALTITUDE);
                        if (extrudeMode == Polyline.HEIGHT_EXTRUDE_PER_POINT)
                            height += baseAltitude
                                    - GeoPoint.MIN_ACCEPTABLE_ALTITUDE;
                    }

                    // Store terrain elevation in point buffer
                    int p = 0;
                    for (double alt : alts) {
                        _points.put(p + 2, alt);
                        p += 3;
                    }
                }

                // Generate height offsets to create flat top/bottom effect
                double[] heights;
                if (extrudeMode != Polyline.HEIGHT_EXTRUDE_PER_POINT) {
                    heights = new double[alts.length];
                    for (int i = 0; i < alts.length; i++)
                        heights[i] = (baseAltitude + height) - alts[i];
                } else
                    heights = new double[] {
                            height
                    };

                if (renderPolygon) {
                    _3dPointsPreForward = GLExtrude.extrudeRelative(
                            Double.NaN, _points, 3, _closed, heights);
                    _3dPoints = Unsafe.allocateDirect(
                            _3dPointsPreForward.limit(), FloatBuffer.class);
                    _3dPointsPreForward.rewind();

                    final int idlInfo = GLAntiMeridianHelper
                            .normalizeHemisphere(3, _3dPointsPreForward,
                                    _3dPointsPreForward);
                    _3dPointsPreForward.flip();
                    _extrudePrimaryHemi = (idlInfo
                            & GLAntiMeridianHelper.MASK_PRIMARY_HEMISPHERE);
                    _extrudeCrossesIDL = (idlInfo
                            & GLAntiMeridianHelper.MASK_IDL_CROSS) != 0;
                }

                if (renderOutline) {
                    _outlinePointsPreForward = GLExtrude.extrudeOutline(
                            Double.NaN, _points, 3, _closed,
                            simpleOutline, heights);
                    _outlinePoints = Unsafe.allocateDirect(
                            _outlinePointsPreForward.limit(),
                            FloatBuffer.class);
                    _outlinePointsPreForward.rewind();

                    final int idlInfo = GLAntiMeridianHelper
                            .normalizeHemisphere(3, _outlinePointsPreForward,
                                    _outlinePointsPreForward);
                    _outlinePointsPreForward.flip();
                    _extrudePrimaryHemi = (idlInfo
                            & GLAntiMeridianHelper.MASK_PRIMARY_HEMISPHERE);
                    _extrudeCrossesIDL = (idlInfo
                            & GLAntiMeridianHelper.MASK_IDL_CROSS) != 0;
                }

                _shouldReextrude = false;
            }

            // extrusion vertices (fill+outline) need to be rebuilt when projection changes
            final boolean rebuildExtrusionVertices = (_extrusionCentroidSrid != ortho.currentPass.drawSrid);
            if (rebuildExtrusionVertices) {
                ortho.currentPass.scene.mapProjection
                        .forward(_extrusionCentroid, _extrusionCentroidProj);
                _extrusionCentroidSrid = ortho.currentPass.drawSrid;
            }

            // set up model-view matrix
            GLES20FixedPipeline.glMatrixMode(GLES20FixedPipeline.GL_MODELVIEW);
            GLES20FixedPipeline.glPushMatrix();

            ortho.scratch.matrix.set(ortho.currentPass.scene.forward);
            // apply hemisphere shift if necessary
            final double unwrap = GLAntiMeridianHelper.getUnwrap(ortho,
                    _extrudeCrossesIDL, _extrudePrimaryHemi);
            ortho.scratch.matrix.translate(unwrap, 0d, 0d);
            // translate relative-to-center for extrusion geometry
            ortho.scratch.matrix.translate(_extrusionCentroidProj.x,
                    _extrusionCentroidProj.y, _extrusionCentroidProj.z);
            // upload model-view transform
            ortho.scratch.matrix.get(ortho.scratch.matrixD,
                    Matrix.MatrixOrder.COLUMN_MAJOR);
            for (int i = 0; i < 16; i++)
                ortho.scratch.matrixF[i] = (float) ortho.scratch.matrixD[i];
            GLES20FixedPipeline.glLoadMatrixf(ortho.scratch.matrixF, 0);

            GLES20FixedPipeline
                    .glEnableClientState(GLES20FixedPipeline.GL_VERTEX_ARRAY);

            GLES20FixedPipeline.glEnable(GLES20FixedPipeline.GL_BLEND);
            GLES20FixedPipeline.glBlendFunc(GLES20FixedPipeline.GL_SRC_ALPHA,
                    GLES20FixedPipeline.GL_ONE_MINUS_SRC_ALPHA);
            int color = fill ? fillColor : strokeColor;
            float r = Color.red(color) / 255.0f;
            float g = Color.green(color) / 255.0f;
            float b = Color.blue(color) / 255.0f;
            float a = fill ? Color.alpha(color) / 255.0f : 0.5f;

            if (renderPolygon) {
                // validate the render vertices
                if (rebuildExtrusionVertices) {
                    _3dPoints.clear();
                    for (int i = 0; i < _3dPointsPreForward.limit() / 3; i++) {
                        final double lng = _3dPointsPreForward.get(i * 3);
                        final double lat = _3dPointsPreForward.get(i * 3 + 1);
                        final double alt = _3dPointsPreForward.get(i * 3 + 2);
                        ortho.scratch.geo.set(lat, lng, alt);
                        ortho.currentPass.scene.mapProjection.forward(
                                ortho.scratch.geo, ortho.scratch.pointD);
                        _3dPoints.put((float) (ortho.scratch.pointD.x
                                - _extrusionCentroidProj.x));
                        _3dPoints.put((float) (ortho.scratch.pointD.y
                                - _extrusionCentroidProj.y));
                        _3dPoints.put((float) (ortho.scratch.pointD.z
                                - _extrusionCentroidProj.z));
                    }
                    _3dPoints.flip();
                }

                GLES20FixedPipeline.glColor4f(r, g, b, a);

                GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL);
                GLES30.glPolygonOffset(1.0f, 1.0f);

                GLES20FixedPipeline.glVertexPointer(3,
                        GLES20FixedPipeline.GL_FLOAT, 0, _3dPoints);

                int pCount = _3dPoints.limit() / 3;

                // Simple order independent transparency, only apply when needed
                if (a < 1.0) {
                    // Assumes shape is convex, although this also works for concave shapes that use the
                    // same color throughout the mesh. In practice you can't tell the difference between
                    // this and _much_ more expensive OIT implementations for _this_ use case.
                    // Works under the assumption that back facing polygons are behind front facing,
                    // preserving correct back to front ordering.
                    GLES20FixedPipeline.glEnable(GLES30.GL_CULL_FACE);
                    GLES20FixedPipeline.glCullFace(GLES30.GL_FRONT);
                    GLES20FixedPipeline.glDrawArrays(GLES30.GL_TRIANGLES, 0,
                            pCount);

                    GLES20FixedPipeline.glCullFace(GLES30.GL_BACK);
                    GLES20FixedPipeline.glDrawArrays(GLES30.GL_TRIANGLES, 0,
                            pCount);
                    GLES20FixedPipeline.glDisable(GLES30.GL_CULL_FACE);
                } else {
                    GLES20FixedPipeline.glDrawArrays(GLES30.GL_TRIANGLES, 0,
                            pCount);
                }

                GLES30.glPolygonOffset(0.0f, 0.0f);
                GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL);
            }

            // Outline around height polygon (only when map is tilted)
            if (renderOutline) {
                // validate the render vertices
                if (rebuildExtrusionVertices) {
                    _outlinePoints.clear();
                    for (int i = 0; i < _outlinePointsPreForward.limit()
                            / 3; i++) {
                        final double lng = _outlinePointsPreForward.get(i * 3);
                        final double lat = _outlinePointsPreForward
                                .get(i * 3 + 1);
                        final double alt = _outlinePointsPreForward
                                .get(i * 3 + 2);
                        ortho.scratch.geo.set(lat, lng, alt);
                        ortho.currentPass.scene.mapProjection.forward(
                                ortho.scratch.geo, ortho.scratch.pointD);
                        _outlinePoints.put((float) (ortho.scratch.pointD.x
                                - _extrusionCentroidProj.x));
                        _outlinePoints.put((float) (ortho.scratch.pointD.y
                                - _extrusionCentroidProj.y));
                        _outlinePoints.put((float) (ortho.scratch.pointD.z
                                - _extrusionCentroidProj.z));
                    }
                    _outlinePoints.flip();
                }

                GLES20FixedPipeline.glLineWidth(this.strokeWeight
                        / ortho.currentPass.relativeScaleHint);
                GLES20FixedPipeline.glVertexPointer(3, GLES30.GL_FLOAT, 0,
                        _outlinePoints);
                GLES20FixedPipeline.glColor4f(r * .9f, g * .9f, b * .9f, 1.0f);
                GLES20FixedPipeline.glDrawArrays(GLES30.GL_LINES, 0,
                        _outlinePoints.limit() / 3);
            }

            GLES20FixedPipeline
                    .glDisableClientState(GLES20FixedPipeline.GL_VERTEX_ARRAY);
            GLES20FixedPipeline.glDisable(GLES20FixedPipeline.GL_BLEND);
            GLES20FixedPipeline.glPopMatrix();
        }

        // Altitude mode toggle
        final boolean drawGeom = !(sprites
                && altitudeMode == AltitudeMode.ClampToGround
                || surface && altitudeMode != AltitudeMode.ClampToGround);

        if (drawGeom) {
            if (currentDraw != ortho.drawVersion)
                recompute = true;
            currentDraw = ortho.drawVersion;

            if (this.needsProjectVertices)
                _projectVerts(ortho);

            if (stroke || (fill && _closed)) {

                GLES20FixedPipeline.glPushMatrix();
                GLES20FixedPipeline.glLoadIdentity();

                this.impl.draw(ortho);

                GLES20FixedPipeline.glPopMatrix();
            }
            this.recompute = false;
        }
        if (sprites)
            drawLabels(ortho);
    }

    private void validateLabels(GLMapView ortho) {
        if (labelsVersion == ortho.currentScene.drawVersion)
            return;
        labelsVersion = ortho.currentScene.drawVersion;
        _labels.clear();

        boolean drawCenterLabel = _subject.hasMetaValue("minRenderScale")
                && Globe.getMapScale(ortho.getSurface().getDpi(),
                        ortho.currentScene.drawMapResolution) >= _subject
                                .getMetaDouble(
                                        "minRenderScale",
                                        DEFAULT_MIN_RENDER_SCALE);

        double minRes = _subject.getMetaDouble("minLabelRenderResolution",
                Polyline.DEFAULT_MIN_LABEL_RENDER_RESOLUTION);
        double maxRes = _subject.getMetaDouble("maxLabelRenderResolution",
                Polyline.DEFAULT_MAX_LABEL_RENDER_RESOLUTION);

        drawCenterLabel |= ortho.currentScene.drawMapResolution > minRes
                && ortho.currentScene.drawMapResolution < maxRes;

        try {
            if (drawCenterLabel && _subject.hasMetaValue("centerPointLabel")) {
                SegmentLabel lbl = new SegmentLabel();
                if (_cplabel == null) {
                    MapTextFormat textFormat = new MapTextFormat(_labelTypeface,
                            _labelTextSize);
                    _cplabel = GLText.getInstance(textFormat);
                }
                lbl.renderer = _cplabel;
                final String _text = _subject.getMetaString(
                        "centerPointLabel", "");
                lbl.text = _text.split("\n");
                lbl.textWidth = _cplabel.getStringWidth(_text);
                lbl.textHeight = _cplabel.getStringHeight(_text);
                lbl.lla = new GeoPoint(centerPoint);

                lbl.textOffx = -(lbl.textWidth / 2.0f);
                lbl.patch = GLRenderGlobals.get(ortho).getSmallNinePatch();
                lbl.patchOffx = -4f;
                lbl.patchOffy = -lbl.renderer.getDescent();
                lbl.patchWidth = lbl.textWidth + 8f;
                lbl.patchHeight = lbl.textHeight;
                _labels.add(lbl);
            }
            validateSegmentLabels(ortho);
            if (numPoints > 1 && _subject.hasMetaValue("labels_on")) {
                String lineLabel = _subject.getLineLabel();
                if (!FileSystemUtils.isEquals(_lineLabel, lineLabel)) {
                    _lineLabel = lineLabel;
                    _lineLabelArr = lineLabel.split("\n");
                    if (_label == null) {
                        MapTextFormat textFormat = new MapTextFormat(
                                _labelTypeface, _labelTextSize);
                        _label = GLText.getInstance(textFormat);
                    }
                    _lineLabelWidth = _label.getStringWidth(lineLabel);
                    _lineLabelHeight = _label.getStringHeight(lineLabel);
                }
                if (!StringUtils.isBlank(_lineLabel))
                    validateFloatingLabel(ortho);
            }
        } catch (Exception cme) {
            // catch and ignore - without adding performance penalty to the whole
            // metadata arch. It will clean up on the next draw.
            Log.e(TAG,
                    "concurrent modification of the segment labels occurred during display");
        }
    }

    protected void drawLabels(GLMapView ortho) {
        validateLabels(ortho);

        for (SegmentLabel lbl : _labels) {
            if (lbl.text.length == 0)
                continue;

            ortho.forward(lbl.lla, ortho.scratch.pointD);
            GLES20FixedPipeline.glPushMatrix();
            float xpos = (float) ortho.scratch.pointD.x;
            float ypos = (float) ortho.scratch.pointD.y;
            float zpos = (float) ortho.scratch.pointD.z;

            if (altitudeMode == AltitudeMode.ClampToGround
                    && ortho.currentPass.drawTilt > 0d)
                ypos += lbl.textHeight;

            GLES20FixedPipeline.glTranslatef(xpos, ypos, zpos);
            GLES20FixedPipeline.glRotatef(lbl.textAngle, 0f, 0f, 1f);
            GLES20FixedPipeline.glTranslatef(lbl.textOffx, lbl.textOffy, 0f);

            if (lbl.patch != null) {
                GLES20FixedPipeline.glColor4f(lbl.patch_r, lbl.patch_g,
                        lbl.patch_b, lbl.patch_a);
                GLES20FixedPipeline.glPushMatrix();
                GLES20FixedPipeline.glTranslatef(lbl.patchOffx, lbl.patchOffy,
                        0f);
                lbl.patch.draw(lbl.patchWidth, lbl.patchHeight);
                GLES20FixedPipeline.glPopMatrix();
            }
            for (int j = 0; j < lbl.text.length; j++) {
                if (lbl.text[j].length() == 0)
                    continue;
                GLES20FixedPipeline.glPushMatrix();
                GLES20FixedPipeline.glTranslatef(0f,
                        ((lbl.text.length - 1) - j)
                                * lbl.renderer.getCharHeight(),
                        0f);
                lbl.renderer.draw(GLText.localize(lbl.text[j]), lbl.text_r,
                        lbl.text_g, lbl.text_b, lbl.text_a);
                GLES20FixedPipeline.glPopMatrix();
            }

            GLES20FixedPipeline.glPopMatrix();
        }
    }

    /**
     * Clips the specified segment against the specified region
     * @param sx    The segment start point, x component
     * @param sy    The segment start point, y component
     * @param ex    The segment end point, x component
     * @param ey    The segment end point, y component
     * @param minX  The minimum x component of the clip region
     * @param minY  The minimum y component of the clip region
     * @param maxX  The maximum x component of the clip region
     * @param maxY  The maximum y component of the clip region
     * @param seg   Returns the clipped segment, must be able to store at least 4 elements
     * @return  <code>0</code> if the segment was completely contained, <code>1</code> if a clipped segment was generated, <code>-1</code> if the segment was completely outside the region
     */
    private static int clip(float sx, float sy, float ex, float ey, float minX,
            float minY, float maxX, float maxY, float[] seg) {
        if (!Rectangle.intersects(Math.min(sx, ex), Math.min(sy, ey),
                Math.max(sx, ex), Math.max(sy, ey), minX, minY, maxX, maxY))
            return -1;

        if (Rectangle.contains(minX, minY, maxX, maxY, Math.min(sx, ex),
                Math.min(sy, ey), Math.max(sx, ex), Math.max(sy, ey))) {
            seg[0] = sx;
            seg[1] = sy;
            seg[2] = ex;
            seg[3] = ey;
            return 0;
        } else {
            // NOTE: top corresponds to miny, bottom corresponds to maxy
            final int left = 0x01;
            final int right = 0x02;
            final int bottom = 0x04;
            final int top = 0x08;

            int s_isect = 0;
            if (sx < minX)
                s_isect |= left;
            if (sx > maxX)
                s_isect |= right;
            if (sy < minY)
                s_isect |= top;
            if (sy > maxY)
                s_isect |= bottom;

            int e_isect = 0;
            if (ex < minX)
                e_isect |= left;
            if (ex > maxX)
                e_isect |= right;
            if (ey < minY)
                e_isect |= top;
            if (ey > maxY)
                e_isect |= bottom;

            final float dx = (ex - sx);
            final float dy = (ey - sy);

            if (s_isect != 0) {
                if (MathUtils.hasBits(s_isect, bottom)) {
                    sx = sx + (ex - sx) * (maxY - sy) / dy;
                    sy = maxY;
                }
                if (MathUtils.hasBits(s_isect, top)) {
                    sx = sx + (ex - sx) * (minY - sy) / dy;
                    sy = minY;
                }
                if (MathUtils.hasBits(s_isect, right)) {
                    sx = maxX;
                    sy = sy + (ey - sy) * (maxX - sx) / dx;
                }
                if (MathUtils.hasBits(s_isect, left)) {
                    sx = minX;
                    sy = sy + (ey - sy) * (minX - sx) / dx;
                }
            }
            if (e_isect != 0) {
                if (MathUtils.hasBits(e_isect, bottom)) {
                    ex = sx + (ex - sx) * (maxY - sy) / dy;
                    ey = maxY;
                }
                if (MathUtils.hasBits(e_isect, top)) {
                    ex = sx + (ex - sx) * (minY - sy) / dy;
                    ey = minY;
                }
                if (MathUtils.hasBits(e_isect, right)) {
                    ex = maxX;
                    ey = sy + (ey - sy) * (maxX - sx) / dx;
                }
                if (MathUtils.hasBits(e_isect, left)) {
                    ex = minX;
                    ey = sy + (ey - sy) * (minX - sx) / dx;
                }
            }

            seg[0] = sx;
            seg[1] = sy;
            seg[2] = ex;
            seg[3] = ey;
            return 1;
        }
    }

    private void getRenderPoint(GLMapView ortho, int idx, GeoPoint geo) {
        if (idx < 0 || _points == null || idx >= _points.limit() / _pointsSize)
            return;
        final double lat = _points.get(idx * _pointsSize + 1);
        final double lng = _points.get(idx * _pointsSize);
        geo.set(lat, lng);

        // source altitude is populated for absolute or relative IF 3 elements specified
        final double alt = (_pointsSize == 3
                && altitudeMode != AltitudeMode.ClampToGround)
                        ? _points.get(idx * _pointsSize + 2)
                        : 0d;
        // terrain is populated for clamp-to-ground or relative
        final double terrain = (altitudeMode != AltitudeMode.Absolute)
                ? ortho.getTerrainMeshElevation(lat, lng)
                : 0d;

        geo.set(alt + terrain);
    }

    private void validateFloatingLabel(GLMapView ortho) {
        if (_label == null) {
            MapTextFormat textFormat = new MapTextFormat(_labelTypeface,
                    _labelTextSize);
            _label = GLText
                    .getInstance(textFormat);
        }

        float[] clippedSegment = new float[4];
        double stripLength = 0d;
        double[] segmentLengths = new double[(_points.limit() / _pointsSize)
                - 1];
        double xmin = Double.MAX_VALUE, ymin = Double.MAX_VALUE,
                xmax = Double.MIN_VALUE, ymax = Double.MIN_VALUE;

        GeoPoint startGeo = GeoPoint.createMutable();
        GeoPoint endGeo = GeoPoint.createMutable();

        getRenderPoint(ortho, 0, endGeo);

        PointF start = new PointF(0, 0);
        PointF end = new PointF(0, 0);
        ortho.currentScene.scene.forward(endGeo, end);

        int stripStartIdx = -1;
        for (int i = 0; i < (_points.limit() / _pointsSize) - 1; i++) {
            startGeo.set(endGeo);
            getRenderPoint(ortho, i + 1, endGeo);

            ortho.currentScene.scene.forward(startGeo, ortho.scratch.pointF);
            final float x0 = ortho.scratch.pointF.x;
            final float y0 = ortho.scratch.pointF.y;
            ortho.currentScene.scene.forward(endGeo, ortho.scratch.pointF);
            final float x1 = ortho.scratch.pointF.x;
            final float y1 = ortho.scratch.pointF.y;

            // clip the segment
            final int result = clip(
                    x0, y0,
                    x1, y1,
                    ortho.currentScene.left,
                    ortho.currentScene.bottom,
                    ortho.currentScene.right,
                    ortho.currentScene.top,
                    clippedSegment);
            if (result != 0) {
                // check emit
                if (stripStartIdx != -1 &&
                        (Math.max(xmax - xmin,
                                ymax - ymin) >= _lineLabelWidth)) {

                    // locate the segment that contains the midpoint of the current strip
                    final double halfLength = stripLength * 0.5;
                    double t = 0d;
                    int containingSegIdx = -1;
                    for (int j = 0; j < (i - 1) - stripStartIdx; j++) {
                        t += segmentLengths[j];
                        if (t > halfLength) {
                            containingSegIdx = j + stripStartIdx;
                            break;
                        }
                    }
                    if (containingSegIdx >= 0) {
                        final double segStart = t
                                - segmentLengths[containingSegIdx
                                        - stripStartIdx];
                        final double segPercent = (halfLength - segStart)
                                / segmentLengths[containingSegIdx
                                        - stripStartIdx];

                        getRenderPoint(ortho, containingSegIdx,
                                ortho.scratch.geo);
                        ortho.currentScene.scene.forward(ortho.scratch.geo,
                                ortho.scratch.pointF);
                        final float segStartX = ortho.scratch.pointF.x;
                        final float segStartY = ortho.scratch.pointF.y;
                        getRenderPoint(ortho, containingSegIdx + 1,
                                ortho.scratch.geo);
                        ortho.currentScene.scene.forward(ortho.scratch.geo,
                                ortho.scratch.pointF);
                        final float segEndX = ortho.scratch.pointF.x;
                        final float segEndY = ortho.scratch.pointF.y;

                        float[] seg = new float[4];
                        clip(
                                segStartX,
                                segStartY,
                                segEndX,
                                segEndY,
                                ortho.currentScene.left,
                                ortho.currentScene.bottom,
                                ortho.currentScene.right,
                                ortho.currentScene.top,
                                seg);

                        final float px = seg[0]
                                + (seg[2] - seg[0]) * (float) segPercent;
                        final float py = seg[1]
                                + (seg[3] - seg[1]) * (float) segPercent;

                        final double segNormalX = (seg[2] - seg[0])
                                / segmentLengths[containingSegIdx
                                        - stripStartIdx];
                        final double segNormalY = (seg[3] - seg[1])
                                / segmentLengths[containingSegIdx
                                        - stripStartIdx];

                        start.x = (float) (px - segNormalX);
                        start.y = (float) (py - segNormalY);
                        end.x = (float) (px + segNormalX);
                        end.y = (float) (py + segNormalY);

                        SegmentLabel lbl = buildTextLabel(ortho,
                                origPoints[containingSegIdx],
                                origPoints[containingSegIdx + 1], start, end,
                                _label, _lineLabel);

                        // recompute LLA
                        final double weight = MathUtils.distance(_textPoint[0],
                                _textPoint[1], segStartX, segStartY)
                                / MathUtils.distance(segStartX, segStartY,
                                        segEndX, segEndY);
                        lbl.lla = GeoCalculations.pointAtDistance(
                                origPoints[containingSegIdx],
                                origPoints[containingSegIdx + 1],
                                weight);
                        if (altitudeMode == AltitudeMode.ClampToGround)
                            lbl.lla = new GeoPoint(lbl.lla.getLatitude(),
                                    lbl.lla.getLongitude(),
                                    ortho.getTerrainMeshElevation(
                                            lbl.lla.getLatitude(),
                                            lbl.lla.getLongitude()));

                        // set text and patch Y offsets that deviate from defaults
                        lbl.textOffy = -_lineLabelHeight / 2
                                + _label.getDescent();
                        lbl.patchOffy = -_label.getDescent() - 4f;

                        _labels.add(lbl);
                    }
                }

                stripStartIdx = -1;
            }
            // segment was completely outside region
            if (result < 0)
                continue;

            // start a new strip if necessary
            if (stripStartIdx == -1) {
                // reset
                xmin = clippedSegment[0];
                ymin = clippedSegment[1];
                xmax = clippedSegment[0];
                ymax = clippedSegment[1];
                stripLength = 0d;
                stripStartIdx = i;
            }

            // update bounds for current strip
            for (int j = 0; j < 2; j++) {
                final float x = clippedSegment[j * 2];
                final float y = clippedSegment[j * 2 + 1];
                if (x > xmax)
                    xmax = x;
                if (x < xmin)
                    xmin = x;
                if (y > ymax)
                    ymax = y;
                if (y < ymin)
                    ymin = y;
            }
            // record the segment length
            segmentLengths[i - stripStartIdx] = MathUtils.distance(
                    clippedSegment[0], clippedSegment[1], clippedSegment[2],
                    clippedSegment[3]);
            // update total strip length
            stripLength += segmentLengths[i - stripStartIdx];
        }

        // check emit
        if (stripStartIdx != -1 &&
                (Math.max(xmax - xmin, ymax - ymin) >= _lineLabelWidth)) {

            // locate the segment that contains the midpoint of the current strip
            final double halfLength = stripLength * 0.5;
            double t = 0d;
            int containingSegIdx = -1;
            for (int j = 0; j < ((_points.limit() / _pointsSize) - 1)
                    - stripStartIdx; j++) {
                t += segmentLengths[j];
                if (t > halfLength) {
                    containingSegIdx = j + stripStartIdx;
                    break;
                }
            }
            if (containingSegIdx >= 0) {
                final double segStart = t
                        - segmentLengths[containingSegIdx - stripStartIdx];
                final double segPercent = (halfLength - segStart)
                        / segmentLengths[containingSegIdx - stripStartIdx];

                getRenderPoint(ortho, containingSegIdx, ortho.scratch.geo);
                ortho.currentScene.scene.forward(ortho.scratch.geo,
                        ortho.scratch.pointF);
                final float segStartX = ortho.scratch.pointF.x;
                final float segStartY = ortho.scratch.pointF.y;
                getRenderPoint(ortho, containingSegIdx + 1, ortho.scratch.geo);
                ortho.currentScene.scene.forward(ortho.scratch.geo,
                        ortho.scratch.pointF);
                final float segEndX = ortho.scratch.pointF.x;
                final float segEndY = ortho.scratch.pointF.y;

                float[] seg = new float[4];
                clip(
                        segStartX,
                        segStartY,
                        segEndX,
                        segEndY,
                        ortho.currentScene.left,
                        ortho.currentScene.bottom,
                        ortho.currentScene.right,
                        ortho.currentScene.top,
                        seg);

                final float px = seg[0]
                        + (seg[2] - seg[0]) * (float) segPercent;
                final float py = seg[1]
                        + (seg[3] - seg[1]) * (float) segPercent;

                final double segNormalX = (seg[2] - seg[0])
                        / segmentLengths[containingSegIdx - stripStartIdx];
                final double segNormalY = (seg[3] - seg[1])
                        / segmentLengths[containingSegIdx - stripStartIdx];

                start.x = (float) (px - segNormalX);
                start.y = (float) (py - segNormalY);
                end.x = (float) (px + segNormalX);
                end.y = (float) (py + segNormalY);

                SegmentLabel lbl = buildTextLabel(ortho,
                        origPoints[containingSegIdx],
                        origPoints[containingSegIdx + 1], start, end, _label,
                        _lineLabel);

                // recompute LLA
                final double weight = MathUtils.distance(_textPoint[0],
                        _textPoint[1], segStartX, segStartY)
                        / MathUtils.distance(segStartX, segStartY, segEndX,
                                segEndY);
                lbl.lla = GeoCalculations.pointAtDistance(
                        origPoints[containingSegIdx],
                        origPoints[containingSegIdx + 1],
                        weight);
                if (altitudeMode == AltitudeMode.ClampToGround)
                    lbl.lla = new GeoPoint(lbl.lla.getLatitude(),
                            lbl.lla.getLongitude(),
                            ortho.getTerrainMeshElevation(
                                    lbl.lla.getLatitude(),
                                    lbl.lla.getLongitude()));
                // set text and patch Y offsets that deviate from defaults
                lbl.textOffy = -_lineLabelHeight / 2 + _label.getDescent();
                lbl.patchOffy = -_label.getDescent() - 4f;

                _labels.add(lbl);
            }
        }
    }

    private void validateSegmentLabels(GLMapView ortho) {
        if (_label == null) {
            MapTextFormat textFormat = new MapTextFormat(_labelTypeface,
                    _labelTextSize);
            _label = GLText
                    .getInstance(textFormat);
        }

        // check for show middle label flag. This will signal to draw the label in the middle
        // visible line segment
        if (_subject.hasMetaValue("centerLabel")) {
            validateCenterLabel(ortho);
            return;
        }

        if (segmentLabels != null) {
            Map<String, Object> labelBundle;
            int segment = 0;
            String text = "";
            GeoPoint curPoint = GeoPoint.createMutable();
            GeoPoint lastPoint = GeoPoint.createMutable();
            PointF startPoint = new PointF();
            PointF endPoint = new PointF();
            final double mapGSD = ortho.currentScene.drawMapResolution;
            double minGSD;
            for (Map.Entry e : segmentLabels.entrySet()) {
                labelBundle = (Map<String, Object>) e.getValue();
                segment = ((Number) labelBundle.get("segment")).intValue();
                if (segment < 0 || segment >= this.numPoints - 1)
                    continue;

                minGSD = Double.MAX_VALUE;
                if (labelBundle.containsKey("min_gsd"))
                    minGSD = ((Number) labelBundle.get("min_gsd"))
                            .doubleValue();
                if (mapGSD > minGSD)
                    continue;

                text = (String) labelBundle.get("text");

                if (text == null || text.length() == 0)
                    continue;

                getRenderPoint(ortho, segment, lastPoint);
                getRenderPoint(ortho, segment + 1, curPoint);

                ortho.currentScene.scene.forward(lastPoint, startPoint);
                ortho.currentScene.scene.forward(curPoint, endPoint);

                // only draw the text if the label fits within the distance between the end points
                // of the segment. This number is multiplied by 2.5 because circles are polylines
                // and it
                // keeps it so that text can be shown when displaying circles.
                // 4 was chosen because of the number of polylines that make up a circle at this
                // time.
                // It would probably be a good idea to construct a GLCircle in the future?
                // XXX - revisit for the next version

                if ((Math.abs(startPoint.x - endPoint.x) * 2.5) < _label
                        .getStringWidth(text)
                        && (Math.abs(startPoint.y - endPoint.y) * 2.5) < _label
                                .getStringWidth(text)) {

                    continue;
                }

                _labels.add(buildTextLabel(ortho, origPoints[segment],
                        origPoints[segment + 1], startPoint, endPoint, _label,
                        text));
            }
        }
    }

    private SegmentLabel buildTextLabel(
            GLMapView ortho,
            GeoPoint startGeo,
            GeoPoint endGeo,
            PointF startVert,
            PointF endVert,
            GLText theLabel,
            String text) {

        buildLabel(ortho, startVert, endVert);
        GLNinePatch _ninePatch = GLRenderGlobals.get(this.context)
                .getMediumNinePatch();

        SegmentLabel lbl = new SegmentLabel();
        lbl.renderer = theLabel;
        lbl.text = text.split("\n");
        lbl.textWidth = _label.getStringWidth(text);
        lbl.textHeight = _label.getStringHeight(text);

        final double weight = MathUtils.distance(_textPoint[0], _textPoint[1],
                startVert.x, startVert.y)
                / MathUtils.distance(endVert.x, endVert.y, startVert.x,
                        startVert.y);
        lbl.lla = GeoCalculations.pointAtDistance(startGeo, endGeo, weight);

        if (altitudeMode == AltitudeMode.ClampToGround)
            lbl.lla = new GeoPoint(lbl.lla.getLatitude(),
                    lbl.lla.getLongitude(),
                    ortho.getTerrainMeshElevation(
                            lbl.lla.getLatitude(),
                            lbl.lla.getLongitude()));

        lbl.textAngle = _textAngle;
        lbl.textOffx = -lbl.textWidth / 2;
        lbl.textOffy = -lbl.textHeight / 2 + 4;
        lbl.patch = _ninePatch;

        float outlineOffset = -((GLText.getLineCount(text) - 1) * lbl.renderer
                .getBaselineSpacing())
                - 4;
        lbl.patchOffx = -8f;
        lbl.patchOffy = outlineOffset - 4f;
        lbl.patch_r = 0f;
        lbl.patch_g = 0f;
        lbl.patch_b = 0f;
        lbl.patch_a = 0.8f;
        lbl.patchWidth = lbl.textWidth + 16f;
        lbl.patchHeight = lbl.textHeight + 8f;

        return lbl;
    }

    /**
     * find the location the label should be placed and the angle it should be rotated.
     */
    private void buildLabel(GLMapView ortho, PointF startVert, PointF endVert) {

        final float p0x = startVert.x;
        final float p0y = startVert.y;

        final float p1x = endVert.x;
        final float p1y = endVert.y;

        final float xmin = (p0x < p1x) ? p0x : p1x;
        final float ymin = (p0x < p1x) ? p0y : p1y;
        final float xmax = (p0x > p1x) ? p0x : p1x;
        final float ymax = (p0x > p1x) ? p0y : p1y;

        float xmid = (int) (xmin + xmax) * div_2;
        float ymid = (int) (ymin + ymax) * div_2;

        if (!_subject.hasMetaValue("staticLabel")) {
            startVert = new PointF(xmin, ymin);
            endVert = new PointF(xmax, ymax);

            RectF _view = this.getWidgetViewF();
            PointF[] ip = _getIntersectionPoint(_view, startVert, endVert);

            if (ip[0] != null || ip[1] != null) {
                if (ip[0] != null && ip[1] != null) {
                    xmid = (ip[0].x + ip[1].x) / 2.0f;
                    ymid = (ip[0].y + ip[1].y) / 2.0f;
                } else {

                    PointF origin = startVert;
                    if (_view.left < endVert.x && endVert.x < _view.right &&
                            _view.bottom < endVert.y && endVert.y < _view.top) {
                        origin = endVert;
                    }

                    if (ip[0] != null) {
                        // Log.d("SHB", "bottom is clipped");
                        xmid = (ip[0].x + origin.x) / 2.0f;
                        ymid = (ip[0].y + origin.y) / 2.0f;
                    } else {
                        // Log.d("SHB", "top is clipped");
                        xmid = (ip[1].x + origin.x) / 2.0f;
                        ymid = (ip[1].y + origin.y) / 2.0f;
                    }
                }
            }
        }

        _textAngle = (float) (Math.atan2(p0y - p1y, p0x
                - p1x) * div_180_pi);
        _textPoint[0] = xmid;
        _textPoint[1] = ymid;

        if (_textAngle > 90 || _textAngle < -90)
            _textAngle += 180;
    }

    /**
     * Display the label on the center of the middle visible segment
     * 
     * @param ortho
     */
    private void validateCenterLabel(GLMapView ortho) {
        // get the text to display, or return if there is none

        final String clt = centerLabelText;

        if (clt == null || clt.length() == 0)
            return;

        if (recompute) {
            middle = findMiddleVisibleSegment(ortho);
        }

        if (middle == -1)
            return;

        if (_label == null) {
            MapTextFormat textFormat = new MapTextFormat(_labelTypeface,
                    _labelTextSize);
            _label = GLText.getInstance(textFormat);
        }

        PointF startPoint = new PointF();
        PointF endPoint = new PointF();

        GeoPoint lastPoint = GeoPoint.createMutable();
        GeoPoint curPoint = GeoPoint.createMutable();
        getRenderPoint(ortho, middle, lastPoint);
        getRenderPoint(ortho, middle + 1, curPoint);

        ortho.currentScene.scene.forward(lastPoint, startPoint);
        ortho.currentScene.scene.forward(curPoint, endPoint);

        final float centerLabelWidth = _label.getStringWidth(clt);

        // see comment on label code, use a larger number for how this is used.
        if ((Math.abs(startPoint.x - endPoint.x) * 8.0) < centerLabelWidth
                && (Math.abs(startPoint.y - endPoint.y)
                        * 8.0) < centerLabelWidth) {

            return;
        }

        _labels.add(buildTextLabel(ortho, origPoints[middle],
                origPoints[middle + 1], startPoint, endPoint, _label, clt));
    }

    private boolean segContained(final PointF endPt1, final PointF endPt2,
            final RectF visibleView) {
        if (endPt1 == null)
            return false;
        float p1x = endPt1.x;
        float p1y = endPt1.y;
        if (endPt2 == null)
            return false;
        float p2x = endPt2.x;
        float p2y = endPt2.y;
        return ((p1y < visibleView.top &&
                p1x < visibleView.right &&
                p1x > 0 && p1y > 0)
                || (p2y < visibleView.top &&
                        p2x < visibleView.right &&
                        p2x > 0 && p2y > 0));
    }

    /**
     * TODO: Currently a direct copy from GLMapItem - but will be reritten / removed when the final
     * version is done. Retrieve the bounding RectF of the current state of the Map. This accounts
     * for the OrthoMapView's focus, so DropDowns will be accounted for. NOTE- the RectF this
     * returns is not a valid RectF since the origin coordinate is in the lower left (ll is 0,0).
     * Therefore the RectF.contains(PointF) method will not work to determine if a point falls
     * inside the bounds.
     *
     * @return The bounding RectF
     */
    protected RectF getWidgetViewF() {
        return getDefaultWidgetViewF(context);
    }

    /**
     * TODO: Currently a direct copy from GLMapItem - but will be reritten / removed when the final
     * version is done.
     * 
     * @param ctx
     * @return
     */
    protected static RectF getDefaultWidgetViewF(MapRenderer ctx) {
        // Could be in half or third display of dropdown, so use the offset;
        float right = ((GLMapView) ctx).focusx * 2;
        // Could be in portrait mode as well, so change the bottom accordingly
        float top = ((GLMapView) ctx).focusy * 2;
        return new RectF(0f, top - MapView.getMapView().getActionBarHeight(),
                right, 0);
    }

    /**
     * TODO: Currently a direct copy from GLMapItem - but will be reritten / removed when the final
     * version is done. Provides the top and the bottom most intersection points.
     */
    public static PointF[] _getIntersectionPoint(RectF r, PointF cF,
            PointF vF) {

        if (r.left < cF.x && cF.x < r.right && r.bottom < cF.y && cF.y < r.top
                &&
                r.left < vF.x && vF.x < r.right && r.bottom < vF.y
                && vF.y < r.top) {
            return new PointF[] {
                    cF, vF
            };
        }

        PointF[] ret = new PointF[2];
        Vector2D[] rets = new Vector2D[4];
        Vector2D c = new Vector2D(cF.x, cF.y);
        Vector2D v = new Vector2D(vF.x, vF.y);

        Vector2D topLeft = new Vector2D(r.left, r.top);
        Vector2D topRight = new Vector2D(r.right, r.top);
        Vector2D botRight = new Vector2D(r.right, r.bottom);
        Vector2D botLeft = new Vector2D(r.left, r.bottom);

        // Start at top line and go clockwise

        rets[0] = Vector2D
                .segmentToSegmentIntersection(topLeft, topRight, c, v);
        rets[1] = Vector2D.segmentToSegmentIntersection(topRight, botRight, c,
                v);
        rets[2] = Vector2D
                .segmentToSegmentIntersection(botRight, botLeft, c, v);
        rets[3] = Vector2D.segmentToSegmentIntersection(botLeft, topLeft, c, v);

        // Check the returned values - returns both the top and the bottom intersection points.
        for (int i = 0; i < 4; i++) {
            // Check to see if it intersected
            if (rets[i] != null) {
                if (i < 2) {
                    // Log.d("SHB", "interesection detected entry #" + i);
                    if (ret[0] == null)
                        ret[0] = new PointF((float) rets[i].x,
                                (float) rets[i].y);
                    else
                        ret[1] = new PointF((float) rets[i].x,
                                (float) rets[i].y);
                } else {
                    // Log.d("SHB", "interesection detected entry #" + i);
                    if (ret[1] == null)
                        ret[1] = new PointF((float) rets[i].x,
                                (float) rets[i].y);
                    else
                        ret[0] = new PointF((float) rets[i].x,
                                (float) rets[i].y);
                }
            }
        }

        return ret;
    }

    /**
     * Determines which line segments are currently visible and then returns the segment in the
     * middle.
     * 
     * @param ortho
     * @return - The index of the starting point of middle segment
     */
    private int findMiddleVisibleSegment(GLMapView ortho) {
        RectF visibleView = this.getWidgetViewF();
        GeoPoint[] points = origPoints;

        // middle, easiest to find.
        if (points.length > 0) {
            int middle = points.length / 2;
            PointF endPt1 = ortho.forward(points[middle], ortho.scratch.pointF);
            PointF endPt2 = ortho.forward(points[middle + 1],
                    ortho.scratch.pointF);
            boolean contained = segContained(endPt1, endPt2, visibleView);
            if (contained)
                return middle;
        }

        // first quarter or last quarter
        if (points.length > 3) {
            int midmid = points.length / 4;
            PointF endPt1 = ortho.forward(points[midmid], ortho.scratch.pointF);
            PointF endPt2 = ortho.forward(points[midmid + 1],
                    ortho.scratch.pointF);
            boolean contained = segContained(endPt1, endPt2, visibleView);
            if (contained)
                return midmid;

            endPt1 = ortho.forward(points[midmid * 3 - 1],
                    ortho.scratch.pointF);
            endPt2 = ortho
                    .forward(points[midmid * 3], ortho.scratch.pointF);
            contained = segContained(endPt1, endPt2, visibleView);
            if (contained)
                return midmid * 3 - 1;
        }

        // walk till we find.
        for (int i = 0; i < points.length - 1; i++) {
            if (points[i] == null || points[i + 1] == null)
                return -1;

            PointF endPt1 = ortho.forward(points[i], ortho.scratch.pointF);
            PointF endPt2 = ortho.forward(points[i + 1], ortho.scratch.pointF);
            boolean contained = segContained(endPt1, endPt2, visibleView);
            if (contained)
                return i;

        }

        return -1;
    }

    protected void _ensureVertBuffer() {
        if (this.numPoints > 0) {
            if (_verts2 == null
                    || _verts2.capacity() < (this.numPoints + 1)
                            * _verts2Size) {
                // Allocate enough space for the number of points + 1 in case we want to draw a
                // closed polygon
                _verts2 = com.atakmap.lang.Unsafe
                        .allocateDirect(((this.numPoints + 1) * _verts2Size),
                                FloatBuffer.class); // +2 wrap points
                _verts2Ptr = Unsafe.getBufferPointer(_verts2);
            }
            _verts2.clear();
        } else if (this.numPoints == 0) {
            _verts2 = null;
            _verts2Ptr = 0L;
        }
    }

    protected void _projectVerts(final GLMapView ortho) {
        if (recompute && this.numPoints > 0) {
            _ensureVertBuffer();

            AbstractGLMapItem2.forward(ortho, _points, _pointsSize, _verts2,
                    _verts2Size, bounds);

            // close the line if necessary
            if (_closed) {
                _verts2.limit(_verts2.limit() + _verts2Size);
                int idx = this.numPoints * _verts2Size;
                _verts2.put(idx++, _verts2.get(0));
                _verts2.put(idx++, _verts2.get(1));
                if (_verts2Size == 3)
                    _verts2.put(idx++, _verts2.get(2));
            }

            // Build bounds for hit testing
            recomputeScreenRectangles(ortho);
        }
    }

    protected void recomputeScreenRectangles(GLMapView ortho) {
        int maxIdx = (this.numPoints + (_closed ? 1 : 0)) * _verts2Size;
        _verts2.clear();
        int partIdx = 1;
        int partCount = 0;
        PartitionRect partition = !_partitionRects.isEmpty()
                ? _partitionRects.get(0)
                : new PartitionRect();
        int idx = 0;
        for (int i = 0; i < maxIdx; i += _verts2Size) {
            float x = _verts2.get(i);
            float y = _verts2.get(i + 1);

            y = ortho.getTop() - y;

            // Update main bounding rectangle
            if (i == 0) {
                _screenRect.set(x, y, x, y);
            } else {
                _screenRect.set(Math.min(_screenRect.left, x),
                        Math.min(_screenRect.top, y),
                        Math.max(_screenRect.right, x),
                        Math.max(_screenRect.bottom, y));
            }

            // Update partition bounding rectangle
            if (partIdx == 0) {
                partition.set(x, y, x, y);
            } else {
                partition.set(Math.min(partition.left, x),
                        Math.min(partition.top, y),
                        Math.max(partition.right, x),
                        Math.max(partition.bottom, y));
            }

            if (partIdx == Polyline.PARTITION_SIZE
                    || i == maxIdx - _verts2Size) {
                if (partCount >= _partitionRects.size())
                    _partitionRects.add(partition);
                partition.endIndex = idx;
                partCount++;
                partIdx = 0;
                partition = partCount < _partitionRects.size()
                        ? _partitionRects.get(partCount)
                        : new PartitionRect();
                partition.startIndex = idx + 1;
            }
            partIdx++;
            idx++;
        }
        while (partCount < _partitionRects.size())
            _partitionRects.remove(partCount);
    }

    @Override
    public Result hitTest(float screenX, float screenY, float radius) {

        RectF hitRect = new RectF(screenX - radius, screenY - radius,
                screenX + radius, screenY + radius);

        // First check hit on bounding rectangle
        if (!RectF.intersects(_screenRect, hitRect))
            return null;

        // Now check partitions
        List<PartitionRect> hitRects = new ArrayList<>();
        for (PartitionRect r : _partitionRects) {

            // Check hit on partition
            if (!RectF.intersects(r, hitRect))
                continue;

            // Keep track of rectangles we've already hit tested
            hitRects.add(r);

            // Point hit test
            for (int i = r.startIndex; i <= r.endIndex && i < numPoints; i++) {
                int vIdx = i * _verts2Size;
                float x = _verts2.get(vIdx);
                float y = _verts2.get(vIdx + 1);

                // Point not contained in hit rectangle
                if (!hitRect.contains(x, y))
                    continue;

                // Found a hit - return result
                int pIdx = i * 3;
                double lng = _points.get(pIdx);
                double lat = _points.get(pIdx + 1);
                double hae = _points.get(pIdx + 2);
                Result res = new Result();
                res.screenPoint = new PointF(x, y);
                res.geoPoint = new GeoPoint(lat, lng, hae);
                res.hitType = HitType.POINT;
                res.hitIndex = i;
                return res;
            }
        }

        // No point detections and no hit partitions
        if (hitRects.isEmpty())
            return null;

        // Line hit test
        Vector2D touch = new Vector2D(screenX, screenY);
        for (PartitionRect r : hitRects) {
            float lastX = 0, lastY = 0;
            for (int i = r.startIndex; i <= r.endIndex && i <= numPoints; i++) {

                int vIdx = i * _verts2Size;
                float x = _verts2.get(vIdx);
                float y = _verts2.get(vIdx + 1);

                if (i > r.startIndex) {

                    // Find the nearest point on this line based on the point we touched
                    Vector2D nearest = Vector2D.nearestPointOnSegment(touch,
                            new Vector2D(lastX, lastY),
                            new Vector2D(x, y));
                    float nx = (float) nearest.x;
                    float ny = (float) nearest.y;

                    // Check if the nearest point is within rectangle
                    if (!hitRect.contains(nx, ny))
                        continue;

                    Result res = new Result();
                    res.screenPoint = new PointF(nx, ny);
                    res.geoPoint = null; // XXX - Need to lookup inverse later
                    res.hitType = HitType.LINE;
                    res.hitIndex = i - 1;
                    return res;
                }

                lastX = x;
                lastY = y;
            }
        }

        return null;
    }

    @Override
    public void onLabelTextSizeChanged(Polyline p) {
        final int labelTextSize = p.getLabelTextSize();
        final Typeface labelTypeface = p.getLabelTypeface();
        context.queueEvent(new Runnable() {
            @Override
            public void run() {
                _labelTextSize = labelTextSize;
                _labelTypeface = labelTypeface;
            }
        });
    }
}
