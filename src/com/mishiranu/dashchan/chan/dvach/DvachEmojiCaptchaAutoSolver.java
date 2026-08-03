package com.mishiranu.dashchan.chan.dvach;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.Log;
import chan.content.ChanPerformer;
import com.mishiranu.dashchan.content.Preferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.DMatch;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.features2d.BFMatcher;
import org.opencv.features2d.SIFT;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;

/** Local, beta-only matcher for the step-by-step emoji captcha used by 2ch. */
public final class DvachEmojiCaptchaAutoSolver implements AutoCloseable {
	public static final String CAPTCHA_DATA_AUTOMATIC = "slooop_auto_emoji_captcha";
	public static final String CAPTCHA_DATA_AUTOMATIC_FAILURE = "slooop_auto_emoji_captcha_failure";
	public static final String FAILURE_UNCERTAIN = "uncertain";
	public static final String FAILURE_UNAVAILABLE = "unavailable";

	static final int RESULT_UNCERTAIN = -1;
	static final int RESULT_UNAVAILABLE = -2;

	private static final String TAG = "EmojiCaptchaSolver";
	private static final Object OPEN_CV_LOCK = new Object();
	private static volatile boolean openCvInitialized;

	private static final int WINDOW_WIDTH = 60;
	private static final int WINDOW_HEIGHT = 60;
	private static final int EXCLUSION_PADDING = 7;
	private static final double LOWE_RATIO = 0.78;
	private static final double RANSAC_THRESHOLD = 8.0;
	private static final int MIN_CANDIDATE_KEYPOINTS = 12;
	private static final int TARGET_CANDIDATE_KEYPOINTS = 90;
	private static final int MIN_CLUSTER_POINTS = 3;
	private static final int MIN_VARIANT_SUPPORT = 2;
	private static final int STRONG_CLUSTER_POINTS = 5;
	private static final int STRONG_VARIANT_SUPPORT = 4;
	private static final int SPARSE_AFTER_SELECTIONS = 2;
	private static final int SPARSE_CLUSTER_POINTS = 2;
	private static final int SPARSE_VARIANT_SUPPORT = 2;

	private final SIFT captchaDetector;
	private final SIFT[] candidateDetectors;
	private final List<FeatureSet> captchaFeatures = new ArrayList<>();
	private final Map<Long, FeatureSet> candidateFeatures = new HashMap<>();
	private final Map<Long, RawMatch> matchCache = new HashMap<>();
	private final Set<Long> selectedCandidates = new HashSet<>();
	private final List<RectD> excludedWindows = new ArrayList<>();

	private int captchaWidth;
	private int captchaHeight;
	private int selectedCount;
	private boolean captchaPrepared;

	public DvachEmojiCaptchaAutoSolver() {
		ensureOpenCv();
		captchaDetector = SIFT.create(0, 5, 0.025, 20, 1.6);
		candidateDetectors = new SIFT[] {
				SIFT.create(0, 3, 0.04, 10, 1.2),
				SIFT.create(0, 5, 0.03, 20, 1.2),
				SIFT.create(0, 10, 0.02, 30, 1.2),
				SIFT.create(0, 15, 0.01, 40, 1.2)
		};
	}

	public static boolean isEnabled() {
		return Preferences.isDvachEmojiCaptchaAutoSolverEnabled();
	}

	public static long getCooldownRemaining() {
		return Preferences.getDvachEmojiCaptchaAutoSolverCooldownRemaining();
	}

	public static void markSuccessfulPost(ChanPerformer.SendPostData data) {
		if (data != null && data.captchaData != null &&
				"1".equals(data.captchaData.get(CAPTCHA_DATA_AUTOMATIC))) {
			Preferences.markDvachEmojiCaptchaAutoSolverSuccessfulUse();
		}
	}

	private static void ensureOpenCv() {
		if (!openCvInitialized) {
			synchronized (OPEN_CV_LOCK) {
				if (!openCvInitialized) {
					if (!OpenCVLoader.initLocal()) {
						throw new IllegalStateException("OpenCV initialization failed");
					}
					openCvInitialized = true;
				}
			}
		}
	}

	int select(Bitmap captcha, Bitmap[] keyboard) {
		try {
			if (!captchaPrepared) {
				prepareCaptcha(captcha);
			}
			ArrayList<CandidateScore> scores = new ArrayList<>();
			for (int i = 0; i < keyboard.length; i++) {
				long key = fingerprint(keyboard[i]);
				if (!selectedCandidates.contains(key)) {
					scores.add(scoreCandidate(i, key, keyboard[i]));
				}
			}
			if (scores.isEmpty()) {
				return RESULT_UNCERTAIN;
			}
			Collections.sort(scores, Comparator.comparingDouble((CandidateScore score) -> score.rankScore).reversed());
			CandidateScore best = scores.get(0);
			CandidateScore second = scores.size() > 1 ? scores.get(1) : null;
			if (!isConfident(best, second)) {
				return RESULT_UNCERTAIN;
			}
			exclude(best.rect);
			selectedCandidates.add(best.key);
			selectedCount++;
			return best.index;
		} catch (RuntimeException e) {
			Log.w(TAG, "Local captcha recognition failed", e);
			return RESULT_UNAVAILABLE;
		}
	}

	private void prepareCaptcha(Bitmap bitmap) {
		releaseCaptchaFeatures();
		Mat rgba = bitmapToMatOnWhite(bitmap);
		captchaWidth = rgba.cols();
		captchaHeight = rgba.rows();
		Mat rgb = new Mat();
		Mat lab = new Mat();
		Mat grayscale = new Mat();
		Mat equalized = new Mat();
		Mat grayscaleClahe = new Mat();
		List<Mat> channels = new ArrayList<>();
		CLAHE clahe = Imgproc.createCLAHE(5.0, new Size(4, 4));
		try {
			Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
			Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab);
			Core.split(lab, channels);
			for (int i = 0; i < Math.min(3, channels.size()); i++) {
				Mat processed = new Mat();
				Mat inverted = new Mat();
				try {
					clahe.apply(channels.get(i), processed);
					Core.bitwise_not(processed, inverted);
					addCaptchaVariant(processed);
					addCaptchaVariant(inverted);
				} finally {
					processed.release();
					inverted.release();
				}
			}

			Imgproc.cvtColor(rgba, grayscale, Imgproc.COLOR_RGBA2GRAY);
			Imgproc.equalizeHist(grayscale, equalized);
			clahe.apply(grayscale, grayscaleClahe);
			for (Mat base : new Mat[] {grayscale, equalized, grayscaleClahe}) {
				Mat inverted = new Mat();
				try {
					Core.bitwise_not(base, inverted);
					addCaptchaVariant(base);
					addCaptchaVariant(inverted);
				} finally {
					inverted.release();
				}
			}
		} finally {
			clahe.clear();
			for (Mat channel : channels) {
				channel.release();
			}
			grayscaleClahe.release();
			equalized.release();
			grayscale.release();
			lab.release();
			rgb.release();
			rgba.release();
		}
		if (captchaFeatures.isEmpty()) {
			throw new IllegalStateException("No captcha features found");
		}
		captchaPrepared = true;
	}

	private void addCaptchaVariant(Mat variant) {
		FeatureSet features = extractFeatures(variant, captchaDetector);
		if (features != null) {
			captchaFeatures.add(features);
		}
	}

	private FeatureSet getCandidateFeatures(long key, Bitmap bitmap) {
		FeatureSet cached = candidateFeatures.get(key);
		if (cached != null) {
			return cached;
		}
		Mat rgba = bitmapToMatOnWhite(bitmap);
		Mat grayscale = new Mat();
		Mat upscaled = new Mat();
		FeatureSet selected = null;
		try {
			Imgproc.cvtColor(rgba, grayscale, Imgproc.COLOR_RGBA2GRAY);
			Imgproc.resize(grayscale, upscaled, new Size(grayscale.cols() * 2.0, grayscale.rows() * 2.0),
					0.0, 0.0, Imgproc.INTER_CUBIC);
			for (SIFT detector : candidateDetectors) {
				FeatureSet attempt = extractFeatures(upscaled, detector);
				if (selected != null) {
					selected.close();
				}
				selected = attempt != null ? attempt : new FeatureSet(new MatOfKeyPoint(), new Mat());
				if (selected.keypoints.rows() >= TARGET_CANDIDATE_KEYPOINTS) {
					break;
				}
			}
		} finally {
			upscaled.release();
			grayscale.release();
			rgba.release();
		}
		if (selected == null) {
			selected = new FeatureSet(new MatOfKeyPoint(), new Mat());
		}
		candidateFeatures.put(key, selected);
		return selected;
	}

	private FeatureSet extractFeatures(Mat image, SIFT detector) {
		MatOfKeyPoint keypoints = new MatOfKeyPoint();
		Mat descriptors = new Mat();
		Mat mask = new Mat();
		try {
			detector.detectAndCompute(image, mask, keypoints, descriptors);
			if (descriptors.empty() || keypoints.empty()) {
				keypoints.release();
				descriptors.release();
				return null;
			}
			return new FeatureSet(keypoints, descriptors);
		} catch (RuntimeException e) {
			keypoints.release();
			descriptors.release();
			throw e;
		} finally {
			mask.release();
		}
	}

	private CandidateScore scoreCandidate(int index, long key, Bitmap bitmap) {
		RawMatch raw = matchCache.get(key);
		if (raw == null) {
			raw = createRawMatch(key, bitmap);
			matchCache.put(key, raw);
		}
		Window window = findBestWindow(raw.combinedPoints, excludedWindows);
		int variantSupport = 0;
		int inlierCount = 0;
		for (VariantMatch variant : raw.variants) {
			boolean supports = false;
			for (Point point : variant.points) {
				if (!insideAny(point, excludedWindows) && window.rect.contains(point)) {
					supports = true;
					break;
				}
			}
			if (supports) {
				variantSupport++;
				inlierCount += variant.inlierCount;
			}
		}
		int clusterPoints = window.points.size();
		double rankScore = clusterPoints + Math.min(variantSupport, 6) * 0.35
				+ Math.min(inlierCount, 80) * 0.03;
		return new CandidateScore(index, key, raw.keypointCount, clusterPoints,
				variantSupport, inlierCount, rankScore, window.rect);
	}

	private RawMatch createRawMatch(long key, Bitmap bitmap) {
		FeatureSet candidate = getCandidateFeatures(key, bitmap);
		ArrayList<VariantMatch> variants = new ArrayList<>(captchaFeatures.size());
		UniquePoints combined = new UniquePoints();
		for (FeatureSet captcha : captchaFeatures) {
			VariantMatch variant = collectVariantPoints(candidate, captcha);
			variants.add(variant);
			for (Point point : variant.points) {
				combined.add(point);
			}
		}
		return new RawMatch(candidate.keypoints.rows(), variants, combined.values());
	}

	private VariantMatch collectVariantPoints(FeatureSet candidate, FeatureSet captcha) {
		if (candidate.descriptors.empty() || captcha.descriptors.empty() || captcha.descriptors.rows() < 2) {
			return VariantMatch.EMPTY;
		}
		BFMatcher matcher = BFMatcher.create(Core.NORM_L2, false);
		List<MatOfDMatch> matches = new ArrayList<>();
		try {
			matcher.knnMatch(candidate.descriptors, captcha.descriptors, matches, 2);
			ArrayList<DMatch> accepted = new ArrayList<>();
			for (MatOfDMatch pair : matches) {
				DMatch[] values = pair.toArray();
				if (values.length == 2 && values[0].distance < LOWE_RATIO * values[1].distance) {
					accepted.add(values[0]);
				}
			}
			if (accepted.isEmpty()) {
				return VariantMatch.EMPTY;
			}

			KeyPoint[] candidatePoints = candidate.keypoints.toArray();
			KeyPoint[] captchaPoints = captcha.keypoints.toArray();
			List<DMatch> inlierMatches = accepted;
			int inlierCount = 0;
			if (accepted.size() >= 4) {
				Point[] source = new Point[accepted.size()];
				Point[] destination = new Point[accepted.size()];
				for (int i = 0; i < accepted.size(); i++) {
					DMatch match = accepted.get(i);
					source[i] = candidatePoints[match.queryIdx].pt;
					destination[i] = captchaPoints[match.trainIdx].pt;
				}
				MatOfPoint2f sourceMat = new MatOfPoint2f(source);
				MatOfPoint2f destinationMat = new MatOfPoint2f(destination);
				Mat inliers = new Mat();
				Mat affine = null;
				try {
					affine = Calib3d.estimateAffine2D(sourceMat, destinationMat, inliers,
							Calib3d.RANSAC, RANSAC_THRESHOLD);
					if (affine == null || affine.empty() || inliers.empty()) {
						return VariantMatch.EMPTY;
					}
					ArrayList<DMatch> filtered = new ArrayList<>();
					byte[] flag = new byte[1];
					for (int i = 0; i < accepted.size() && i < inliers.rows(); i++) {
						inliers.get(i, 0, flag);
						if (flag[0] != 0) {
							filtered.add(accepted.get(i));
						}
					}
					inlierMatches = filtered;
					inlierCount = filtered.size();
				} finally {
					if (affine != null) {
						affine.release();
					}
					inliers.release();
					destinationMat.release();
					sourceMat.release();
				}
			}

			UniquePoints points = new UniquePoints();
			for (DMatch match : inlierMatches) {
				points.add(captchaPoints[match.trainIdx].pt);
			}
			List<Point> values = points.values();
			Window cluster = findBestWindow(values, Collections.emptyList());
			return cluster.points.size() >= 2 ? new VariantMatch(cluster.points, inlierCount) : VariantMatch.EMPTY;
		} finally {
			for (MatOfDMatch match : matches) {
				match.release();
			}
			matcher.clear();
		}
	}

	private boolean isConfident(CandidateScore best, CandidateScore second) {
		if (best.keypointCount < MIN_CANDIDATE_KEYPOINTS) {
			return false;
		}
		boolean strong = best.clusterPoints >= STRONG_CLUSTER_POINTS
				&& best.variantSupport >= STRONG_VARIANT_SUPPORT;
		boolean standard = best.clusterPoints >= MIN_CLUSTER_POINTS
				&& best.variantSupport >= MIN_VARIANT_SUPPORT;
		boolean sparseLate = selectedCount >= SPARSE_AFTER_SELECTIONS
				&& best.clusterPoints >= SPARSE_CLUSTER_POINTS
				&& best.variantSupport >= SPARSE_VARIANT_SUPPORT;
		if (!strong && !standard && !sparseLate) {
			return false;
		}
		if (second == null || second.rankScore <= 0.0) {
			return true;
		}
		if (overlapRatio(best.rect, second.rect) < 0.25) {
			return true;
		}
		double margin = best.rankScore - second.rankScore;
		double ratio = best.rankScore / second.rankScore;
		boolean standardLead = margin >= 1.15 && ratio >= 1.08;
		boolean multiSignalLead = best.clusterPoints >= second.clusterPoints
				&& best.variantSupport >= second.variantSupport
				&& best.inlierCount >= second.inlierCount * 1.5
				&& best.inlierCount - second.inlierCount >= 6 && margin >= 0.25;
		if (standardLead || multiSignalLead) {
			return true;
		}
		if (strong && (second.clusterPoints < STRONG_CLUSTER_POINTS
				|| second.variantSupport < STRONG_VARIANT_SUPPORT)) {
			return true;
		}
		return sparseLate && (second.clusterPoints < SPARSE_CLUSTER_POINTS
				|| second.variantSupport < SPARSE_VARIANT_SUPPORT
				|| margin >= 0.65 && ratio >= 1.2);
	}

	private void exclude(RectD rect) {
		double x = clamp(rect.x - EXCLUSION_PADDING, 0.0, captchaWidth);
		double y = clamp(rect.y - EXCLUSION_PADDING, 0.0, captchaHeight);
		double right = clamp(rect.x + rect.width + EXCLUSION_PADDING, 0.0, captchaWidth);
		double bottom = clamp(rect.y + rect.height + EXCLUSION_PADDING, 0.0, captchaHeight);
		excludedWindows.add(new RectD(x, y, right - x, bottom - y));
	}

	private Window findBestWindow(List<Point> points, List<RectD> excluded) {
		double width = Math.min(WINDOW_WIDTH, Math.max(1, captchaWidth));
		double height = Math.min(WINDOW_HEIGHT, Math.max(1, captchaHeight));
		double maxX = Math.max(0.0, captchaWidth - width);
		double maxY = Math.max(0.0, captchaHeight - height);
		ArrayList<Point> available = new ArrayList<>();
		for (Point point : points) {
			if (!insideAny(point, excluded)) {
				available.add(point);
			}
		}
		RectD bestRect = new RectD(0.0, 0.0, width, height);
		List<Point> bestPoints = Collections.emptyList();
		if (available.isEmpty()) {
			return new Window(bestRect, bestPoints);
		}
		Set<Double> xStarts = new HashSet<>();
		xStarts.add(0.0);
		xStarts.add(maxX);
		for (Point point : available) {
			xStarts.add(clamp(point.x, 0.0, maxX));
			xStarts.add(clamp(point.x - width + 0.001, 0.0, maxX));
		}
		for (double x : xStarts) {
			for (Point start : available) {
				double y = clamp(start.y, 0.0, maxY);
				RectD rect = new RectD(x, y, width, height);
				ArrayList<Point> contained = new ArrayList<>();
				for (Point point : available) {
					if (rect.contains(point)) {
						contained.add(point);
					}
				}
				if (contained.size() > bestPoints.size()) {
					bestRect = rect;
					bestPoints = contained;
				}
			}
		}
		return new Window(bestRect, bestPoints);
	}

	private static boolean insideAny(Point point, List<RectD> rects) {
		for (RectD rect : rects) {
			if (rect.contains(point)) {
				return true;
			}
		}
		return false;
	}

	private static double overlapRatio(RectD first, RectD second) {
		double left = Math.max(first.x, second.x);
		double top = Math.max(first.y, second.y);
		double right = Math.min(first.x + first.width, second.x + second.width);
		double bottom = Math.min(first.y + first.height, second.y + second.height);
		double intersection = Math.max(0.0, right - left) * Math.max(0.0, bottom - top);
		double minimumArea = Math.min(first.width * first.height, second.width * second.height);
		return minimumArea > 0.0 ? intersection / minimumArea : 0.0;
	}

	private static Mat bitmapToMatOnWhite(Bitmap bitmap) {
		Bitmap opaque = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(opaque);
		canvas.drawARGB(255, 255, 255, 255);
		canvas.drawBitmap(bitmap, 0f, 0f, null);
		Mat mat = new Mat();
		try {
			Utils.bitmapToMat(opaque, mat);
			return mat;
		} catch (RuntimeException e) {
			mat.release();
			throw e;
		} finally {
			opaque.recycle();
		}
	}

	private static long fingerprint(Bitmap bitmap) {
		int width = bitmap.getWidth();
		int height = bitmap.getHeight();
		int[] pixels = new int[width * height];
		bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
		long hash = 0xcbf29ce484222325L;
		hash = (hash ^ width) * 0x100000001b3L;
		hash = (hash ^ height) * 0x100000001b3L;
		for (int pixel : pixels) {
			hash = (hash ^ pixel) * 0x100000001b3L;
		}
		return hash;
	}

	private static double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	private void releaseCaptchaFeatures() {
		for (FeatureSet features : captchaFeatures) {
			features.close();
		}
		captchaFeatures.clear();
		for (FeatureSet features : candidateFeatures.values()) {
			features.close();
		}
		candidateFeatures.clear();
		matchCache.clear();
		selectedCandidates.clear();
		excludedWindows.clear();
		selectedCount = 0;
		captchaPrepared = false;
	}

	@Override
	public void close() {
		releaseCaptchaFeatures();
		captchaDetector.clear();
		for (SIFT detector : candidateDetectors) {
			detector.clear();
		}
	}

	private static final class FeatureSet implements AutoCloseable {
		final MatOfKeyPoint keypoints;
		final Mat descriptors;

		FeatureSet(MatOfKeyPoint keypoints, Mat descriptors) {
			this.keypoints = keypoints;
			this.descriptors = descriptors;
		}

		@Override
		public void close() {
			keypoints.release();
			descriptors.release();
		}
	}

	private static final class VariantMatch {
		static final VariantMatch EMPTY = new VariantMatch(Collections.emptyList(), 0);
		final List<Point> points;
		final int inlierCount;

		VariantMatch(List<Point> points, int inlierCount) {
			this.points = points;
			this.inlierCount = inlierCount;
		}
	}

	private static final class RawMatch {
		final int keypointCount;
		final List<VariantMatch> variants;
		final List<Point> combinedPoints;

		RawMatch(int keypointCount, List<VariantMatch> variants, List<Point> combinedPoints) {
			this.keypointCount = keypointCount;
			this.variants = variants;
			this.combinedPoints = combinedPoints;
		}
	}

	private static final class CandidateScore {
		final int index;
		final long key;
		final int keypointCount;
		final int clusterPoints;
		final int variantSupport;
		final int inlierCount;
		final double rankScore;
		final RectD rect;

		CandidateScore(int index, long key, int keypointCount, int clusterPoints,
				int variantSupport, int inlierCount, double rankScore, RectD rect) {
			this.index = index;
			this.key = key;
			this.keypointCount = keypointCount;
			this.clusterPoints = clusterPoints;
			this.variantSupport = variantSupport;
			this.inlierCount = inlierCount;
			this.rankScore = rankScore;
			this.rect = rect;
		}
	}

	private static final class RectD {
		final double x;
		final double y;
		final double width;
		final double height;

		RectD(double x, double y, double width, double height) {
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
		}

		boolean contains(Point point) {
			return point.x >= x && point.x < x + width && point.y >= y && point.y < y + height;
		}
	}

	private static final class Window {
		final RectD rect;
		final List<Point> points;

		Window(RectD rect, List<Point> points) {
			this.rect = rect;
			this.points = points;
		}
	}

	private static final class UniquePoints {
		private final Map<String, Point> points = new LinkedHashMap<>();

		void add(Point point) {
			double x = Math.round(point.x * 2.0) / 2.0;
			double y = Math.round(point.y * 2.0) / 2.0;
			points.put(x + ":" + y, new Point(x, y));
		}

		List<Point> values() {
			return new ArrayList<>(points.values());
		}
	}
}
