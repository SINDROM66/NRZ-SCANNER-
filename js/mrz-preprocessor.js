/**
 * mrz-preprocessor.js
 * OpenCV.js WebAssembly MRZ Preprocessing Pipeline
 * 
 * Implements the Senior Principal Architect-recommended pipeline:
 *   1. MRZ Region Crop (bottom 38-82% of card — tighter than 45%)
 *   2. Grayscale Conversion
 *   3. CLAHE (Contrast Limited Adaptive Histogram Equalization)
 *   4. Gaussian Blur (3x3 kernel, sigma=0)
 *   5. Adaptive Gaussian Threshold (blockSize=15, C=10)
 *   6. Morphological Close (2x2 rect kernel)
 * 
 * Target: Ugandan National ID cards with guilloche security patterns
 * Output: Binarized MRZ band optimized for Tesseract.js WebAssembly OCR
 * 
 * @version 2.0.0
 * @requires OpenCV.js 4.x (loaded as WASM module)
 */

(function (global, factory) {
    typeof exports === 'object' && typeof module !== 'undefined'
        ? module.exports = factory()
        : typeof define === 'function' && define.amd
        ? define(factory)
        : (global = typeof globalThis !== 'undefined' ? globalThis : global || self, global.MrzPreprocessor = factory());
})(this, function () {
    'use strict';

    // ─────────────────────────────────────────────────────────────────────────
    // Configuration Constants
    // ─────────────────────────────────────────────────────────────────────────
    const CONFIG = {
        // MRZ crop zone: bottom 38% to 82% of card height (tight band around TD1 MRZ)
        cropYStartRatio: 0.38,
        cropYEndRatio:   0.82,

        // Max dimension for downsample (prevents Tesseract.js memory bloat)
        maxLongSide: 1200,

        // CLAHE parameters
        claheClipLimit: 2.0,
        claheTileSize:  { width: 8, height: 8 },

        // Gaussian blur
        gaussianKernel: { width: 3, height: 3 },
        gaussianSigmaX: 0,

        // Adaptive threshold
        adaptiveBlockSize: 15,   // Must be odd
        adaptiveC: 10,

        // Morphological close
        morphKernelSize: { width: 2, height: 2 },

        // Debug: set to true to draw intermediate stages to hidden canvases
        debug: false
    };

    // ─────────────────────────────────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────────────────────────────────
    let cvReady = false;
    let initCallbacks = [];

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    const MrzPreprocessor = {
        /**
         * Initialize the preprocessor. Waits for OpenCV.js WASM to load.
         * Call this once at app startup.
         * @param {Function} onReady — called when OpenCV.js is ready
         * @param {Function} onError — called if OpenCV.js fails to load
         */
        init: function (onReady, onError) {
            if (typeof cv === 'undefined') {
                const err = new Error('OpenCV.js (cv) is not loaded. Include <script src="opencv.js"> before mrz-preprocessor.js');
                if (onError) onError(err);
                else console.error(err);
                return;
            }

            if (cvReady) {
                if (onReady) onReady();
                return;
            }

            if (onReady) initCallbacks.push(onReady);

            // OpenCV.js may already be initialized, or may fire onRuntimeInitialized
            if (cv.getBuildInformation) {
                _markReady();
            } else if (cv.onRuntimeInitialized) {
                const prev = cv.onRuntimeInitialized;
                cv.onRuntimeInitialized = function () {
                    if (prev) prev();
                    _markReady();
                };
            } else {
                const poll = setInterval(function () {
                    if (cv.getBuildInformation) {
                        clearInterval(poll);
                        _markReady();
                    }
                }, 100);
                setTimeout(function () {
                    clearInterval(poll);
                    if (!cvReady) {
                        const err = new Error('OpenCV.js failed to initialize within 30 seconds');
                        if (onError) onError(err);
                    }
                }, 30000);
            }
        },

        /**
         * Check if OpenCV.js is ready.
         * @returns {boolean}
         */
        isReady: function () {
            return cvReady;
        },

        /**
         * Process an image element or canvas through the full MRZ preprocessing pipeline.
         * 
         * @param {HTMLImageElement|HTMLCanvasElement|HTMLVideoElement} source
         * @param {Object} options — optional overrides for CONFIG
         * @returns {HTMLCanvasElement} A new canvas containing the preprocessed MRZ band
         */
        process: function (source, options) {
            if (!cvReady) {
                throw new Error('MrzPreprocessor not initialized. Call MrzPreprocessor.init() first.');
            }

            const cfg = Object.assign({}, CONFIG, options || {});

            // Step 0: Read source into OpenCV Mat
            const srcMat = cv.imread(source);

            try {
                // Step 1: Crop MRZ Region
                const mrzMat = _cropMrzRegion(srcMat, cfg);

                // Step 2: Downsample if too large
                const resizedMat = _downsampleMaxSide(mrzMat, cfg.maxLongSide);

                // Step 3: Grayscale
                const grayMat = new cv.Mat();
                cv.cvtColor(resizedMat, grayMat, cv.COLOR_RGBA2GRAY);

                // Step 4: CLAHE
                const claheMat = new cv.Mat();
                const tileSize = new cv.Size(cfg.claheTileSize.width, cfg.claheTileSize.height);
                const clahe = new cv.CLAHE(cfg.claheClipLimit, tileSize);
                clahe.apply(grayMat, claheMat);
                clahe.delete();
                tileSize.delete();

                // Step 5: Gaussian Blur
                const blurMat = new cv.Mat();
                const gaussKsize = new cv.Size(cfg.gaussianKernel.width, cfg.gaussianKernel.height);
                cv.GaussianBlur(claheMat, blurMat, gaussKsize, cfg.gaussianSigmaX);
                gaussKsize.delete();

                // Step 6: Adaptive Gaussian Threshold
                const binaryMat = new cv.Mat();
                cv.adaptiveThreshold(
                    blurMat, binaryMat, 255,
                    cv.ADAPTIVE_THRESH_GAUSSIAN_C,
                    cv.THRESH_BINARY,
                    cfg.adaptiveBlockSize,
                    cfg.adaptiveC
                );

                // Step 7: Morphological Close
                const closedMat = new cv.Mat();
                const morphKsize = new cv.Size(cfg.morphKernelSize.width, cfg.morphKernelSize.height);
                const morphKernel = cv.getStructuringElement(cv.MORPH_RECT, morphKsize);
                cv.morphologyEx(binaryMat, closedMat, cv.MORPH_CLOSE, morphKernel);
                morphKernel.delete();
                morphKsize.delete();

                // Step 8: Convert to RGBA canvas for Tesseract.js
                const rgbaMat = new cv.Mat();
                cv.cvtColor(closedMat, rgbaMat, cv.COLOR_GRAY2RGBA);

                const outputCanvas = document.createElement('canvas');
                outputCanvas.width = rgbaMat.cols;
                outputCanvas.height = rgbaMat.rows;
                cv.imshow(outputCanvas, rgbaMat);

                // Debug: draw intermediates
                if (cfg.debug) {
                    _drawDebugStage('debug-gray', grayMat);
                    _drawDebugStage('debug-clahe', claheMat);
                    _drawDebugStage('debug-blur', blurMat);
                    _drawDebugStage('debug-binary', binaryMat);
                    _drawDebugStage('debug-closed', closedMat);
                }

                mrzMat.delete();
                resizedMat.delete();
                grayMat.delete();
                claheMat.delete();
                blurMat.delete();
                binaryMat.delete();
                closedMat.delete();
                rgbaMat.delete();
                srcMat.delete();

                return outputCanvas;

            } catch (err) {
                srcMat.delete();
                throw err;
            }
        },

        /**
         * Lightweight variant: only crop + grayscale + adaptive threshold.
         * Use this on low-end devices where CLAHE + blur causes frame drops.
         * 
         * @param {HTMLImageElement|HTMLCanvasElement} source
         * @param {Object} options
         * @returns {HTMLCanvasElement}
         */
        processFast: function (source, options) {
            if (!cvReady) {
                throw new Error('MrzPreprocessor not initialized. Call MrzPreprocessor.init() first.');
            }

            const cfg = Object.assign({}, CONFIG, options || {});
            const srcMat = cv.imread(source);

            try {
                const mrzMat = _cropMrzRegion(srcMat, cfg);
                const resizedMat = _downsampleMaxSide(mrzMat, cfg.maxLongSide);
                const grayMat = new cv.Mat();
                cv.cvtColor(resizedMat, grayMat, cv.COLOR_RGBA2GRAY);

                const binaryMat = new cv.Mat();
                cv.adaptiveThreshold(
                    grayMat, binaryMat, 255,
                    cv.ADAPTIVE_THRESH_GAUSSIAN_C,
                    cv.THRESH_BINARY,
                    cfg.adaptiveBlockSize,
                    cfg.adaptiveC
                );

                const rgbaMat = new cv.Mat();
                cv.cvtColor(binaryMat, rgbaMat, cv.COLOR_GRAY2RGBA);

                const outputCanvas = document.createElement('canvas');
                outputCanvas.width = rgbaMat.cols;
                outputCanvas.height = rgbaMat.rows;
                cv.imshow(outputCanvas, rgbaMat);

                mrzMat.delete();
                resizedMat.delete();
                grayMat.delete();
                binaryMat.delete();
                rgbaMat.delete();
                srcMat.delete();

                return outputCanvas;
            } catch (err) {
                srcMat.delete();
                throw err;
            }
        },

        /**
         * Get the MRZ region bounding box for spatial filtering of
         * administrative text fields (District, County, etc.).
         * 
         * @param {HTMLImageElement|HTMLCanvasElement} source
         * @returns {Object} { yMin, yMax, height } in pixels relative to source
         */
        getMrzRegionBounds: function (source) {
            const height = source.naturalHeight || source.height || source.videoHeight || 0;
            const width  = source.naturalWidth  || source.width  || source.videoWidth  || 0;
            if (!height || !width) return null;

            return {
                x: 0,
                y: Math.round(height * CONFIG.cropYStartRatio),
                width: width,
                height: Math.round(height * (CONFIG.cropYEndRatio - CONFIG.cropYStartRatio)),
                yMin: Math.round(height * CONFIG.cropYStartRatio),
                yMax: Math.round(height * CONFIG.cropYEndRatio)
            };
        },

        /**
         * Dispose all OpenCV.js resources. Call on app teardown.
         */
        dispose: function () {
            cvReady = false;
            initCallbacks = [];
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Private Helpers
    // ─────────────────────────────────────────────────────────────────────────

    function _markReady() {
        if (cvReady) return;
        cvReady = true;
        console.log('[MrzPreprocessor] OpenCV.js ready. Build:', cv.getBuildInformation().split('\n')[0]);
        initCallbacks.forEach(function (cb) {
            try { cb(); } catch (e) { console.error(e); }
        });
        initCallbacks = [];
    }

    function _cropMrzRegion(srcMat, cfg) {
        const x = 0;
        const y = Math.round(srcMat.rows * cfg.cropYStartRatio);
        const w = srcMat.cols;
        const h = Math.round(srcMat.rows * (cfg.cropYEndRatio - cfg.cropYStartRatio));

        const safeY = Math.max(0, Math.min(y, srcMat.rows - 1));
        const safeH = Math.max(1, Math.min(h, srcMat.rows - safeY));

        const roiRect = new cv.Rect(x, safeY, w, safeH);
        const cropped = srcMat.roi(roiRect);
        roiRect.delete();
        return cropped;
    }

    function _downsampleMaxSide(mat, maxSide) {
        const longSide = Math.max(mat.cols, mat.rows);
        if (longSide <= maxSide) {
            const clone = mat.clone();
            return clone;
        }

        const scale = maxSide / longSide;
        const newW = Math.round(mat.cols * scale);
        const newH = Math.round(mat.rows * scale);
        const dst = new cv.Mat();
        const dsize = new cv.Size(newW, newH);
        cv.resize(mat, dst, dsize, 0, 0, cv.INTER_AREA);
        dsize.delete();
        return dst;
    }

    function _drawDebugStage(id, mat) {
        let canvas = document.getElementById(id);
        if (!canvas) {
            canvas = document.createElement('canvas');
            canvas.id = id;
            canvas.style.cssText = 'position:fixed;bottom:0;right:0;width:200px;height:60px;z-index:9999;border:1px solid red;';
            document.body.appendChild(canvas);
        }
        cv.imshow(canvas, mat);
    }

    return MrzPreprocessor;
});
