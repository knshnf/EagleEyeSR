package neildg.com.megatronsr.processing.multiple.alignment;

import android.util.Log;

import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.DMatch;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

import neildg.com.megatronsr.io.ImageFileAttribute;
import neildg.com.megatronsr.io.FileImageWriter;
import neildg.com.megatronsr.model.AttributeHolder;
import neildg.com.megatronsr.model.AttributeNames;
import neildg.com.megatronsr.ui.ProgressDialogHandler;

import static neildg.com.megatronsr.constants.FilenameConstants.WARP_PREFIX;

/**
 * Created by neil.dg on 3/10/16.
 */
public class LRWarpingOperator {
    private final static String TAG = "WarpingOperator";

    private MatOfKeyPoint refKeypoint;
    private MatOfDMatch[] goodMatchList;
    private MatOfKeyPoint[] keyPointList;
    private Mat[] imagesToWarpList;

    private Mat[] warpedMatList;

    public LRWarpingOperator(MatOfKeyPoint refKeypoint, Mat[] imagesToWarpList, MatOfDMatch[] goodMatchList, MatOfKeyPoint[] keyPointList) {
        this.goodMatchList = goodMatchList;
        this.keyPointList = keyPointList;
        this.refKeypoint = refKeypoint;
        this.imagesToWarpList = imagesToWarpList;

        this.warpedMatList = new Mat[this.imagesToWarpList.length];
    }

    public void perform() {

        for(int i = 0; i < this.imagesToWarpList.length; i++) {
            Mat warpedMat = this.warpImage(this.goodMatchList[i], this.keyPointList[i], this.imagesToWarpList[i]);
            FileImageWriter.getInstance().saveMatrixToImage(warpedMat, WARP_PREFIX +i, ImageFileAttribute.FileType.JPEG);

            this.imagesToWarpList[i].release();
        }

       this.finalizeResult();
    }

    private void finalizeResult() {
        AttributeHolder.getSharedInstance().putValue(AttributeNames.WARPED_IMAGES_LENGTH_KEY, this.imagesToWarpList.length);

        this.refKeypoint.release(); this.refKeypoint = null;
        for(MatOfDMatch dMatch: this.goodMatchList) {
            dMatch.release();
        }
        this.goodMatchList = null;

        for(MatOfKeyPoint keyPoint: this.keyPointList) {
            keyPoint.release();
        }
        this.keyPointList = null;
        this.imagesToWarpList = null;
    }

    public Mat[] getWarpedMatList() {
        return this.warpedMatList;
    }

    private Mat warpImage(MatOfDMatch goodMatch, MatOfKeyPoint candidateKeypoint, Mat candidateMat) {
        MatOfPoint2f matOfPoint1 = new MatOfPoint2f();
        MatOfPoint2f matOfPoint2 = new MatOfPoint2f();

        KeyPoint[] keyPoints1 = this.refKeypoint.toArray();
        KeyPoint[] keyPoints2 = candidateKeypoint.toArray();

        List<Point> pointList1 = new ArrayList<>();
        List<Point> pointList2 = new ArrayList<>();

        DMatch[] dMatchArray = goodMatch.toArray();

        for(int i = 0; i < dMatchArray.length; i++) {
            Log.d(TAG, "DMATCHES" + dMatchArray[i].toString());

            pointList1.add(keyPoints1[dMatchArray[i].queryIdx].pt);
            pointList2.add(keyPoints2[dMatchArray[i].trainIdx].pt);
        }

        matOfPoint1.fromList(pointList1); matOfPoint2.fromList(pointList2);

        //MatOfByte status = new MatOfByte(); MatOfFloat error = new MatOfFloat();
        //Video.calcOpticalFlowPyrLK(this.referenceMat, candidateMat, matOfPoint1, matOfPoint2, status, error);

        //((M0.type() == CV_32F || M0.type() == CV_64F) && M0.rows == 3 && M0.cols == 3)

        Log.d(TAG, "Homography pre info: matOfPoint1 ROWS: " + matOfPoint1.rows() + " matOfPoint1 COLS: " + matOfPoint1.cols());
        Log.d(TAG, "Homography pre info: matOfPoint2 ROWS: " + matOfPoint2.rows() + " matOfPoint2 COLS: " + matOfPoint2.cols());

        Mat homography;
        if(matOfPoint1.rows() > 0 && matOfPoint1.cols() > 0 && matOfPoint2.rows() > 0 && matOfPoint2.cols() >0) {
            homography = Calib3d.findHomography(matOfPoint2, matOfPoint1, Calib3d.RANSAC, 1);
        }
        else {
            homography = new Mat(); //just empty
        }

        Log.d(TAG, "Homography info: ROWS: " + homography.rows() + " COLS: " + homography.cols());

        matOfPoint1.release();
        matOfPoint2.release();
        pointList1.clear();
        pointList2.clear();

        return performPerspectiveWarping(candidateMat, homography);

    }

    public static Mat performPerspectiveWarping(Mat inputMat, Mat homography) {
        if(homography.rows() == 3 && homography.cols() == 3) {
            Mat warpedMat = new Mat();
            Imgproc.warpPerspective(inputMat, warpedMat, homography, warpedMat.size(), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE, Scalar.all(0));

            homography.release();
            return warpedMat;
        }
        else {
            //do nothing. not enough features for warping
            Mat warpedMat = new Mat();
            inputMat.copyTo(warpedMat);

            homography.release();

            Log.e(TAG, "No homography was found for warp perspective. Returning original mat.");
            return warpedMat;
        }
    }
}