package neildg.com.megatronsr.threads;

import android.util.Log;

import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import neildg.com.megatronsr.constants.FilenameConstants;
import neildg.com.megatronsr.constants.ParameterConfig;
import neildg.com.megatronsr.io.BitmapURIRepository;
import neildg.com.megatronsr.io.ImageFileAttribute;
import neildg.com.megatronsr.io.ImageReader;
import neildg.com.megatronsr.model.multiple.ProcessedImageRepo;
import neildg.com.megatronsr.model.multiple.SharpnessMeasure;
import neildg.com.megatronsr.processing.filters.YangFilter;
import neildg.com.megatronsr.processing.imagetools.ColorSpaceOperator;
import neildg.com.megatronsr.processing.imagetools.ImageOperator;
import neildg.com.megatronsr.processing.multiple.fusion.MeanFusionOperator;
import neildg.com.megatronsr.processing.multiple.postprocess.ChannelMergeOperator;
import neildg.com.megatronsr.processing.multiple.refinement.DenoisingOperator;
import neildg.com.megatronsr.processing.multiple.resizing.DownsamplingOperator;
import neildg.com.megatronsr.processing.multiple.selection.TestImagesSelector;
import neildg.com.megatronsr.processing.multiple.warping.AffineWarpingOperator;
import neildg.com.megatronsr.processing.multiple.warping.FeatureMatchingOperator;
import neildg.com.megatronsr.processing.multiple.warping.LRWarpingOperator;
import neildg.com.megatronsr.processing.multiple.resizing.LRToHROperator;
import neildg.com.megatronsr.ui.ProgressDialogHandler;

/**
 * SRProcessor main entry point
 * Created by NeilDG on 3/5/2016.
 */
public class MultipleImageSRProcessor extends Thread {
    private final static String TAG = "MultipleImageSR";

    public MultipleImageSRProcessor() {

    }

    @Override
    public void run() {
        ProgressDialogHandler.getInstance().showDialog("Downsampling images", "Downsampling images selected and saving them in file.");

        //initialize storage classes
        ProcessedImageRepo.initialize();
        SharpnessMeasure.initialize();

        //downsample
        DownsamplingOperator downsamplingOperator = new DownsamplingOperator(ParameterConfig.getScalingFactor(), BitmapURIRepository.getInstance().getNumImagesSelected());
        downsamplingOperator.perform();

        ProgressDialogHandler.getInstance().hideDialog();

        //simulate degradation
        //DegradationOperator degradationOperator = new DegradationOperator();
        //degradationOperator.perform();

        //load images and use Y channel as input for succeeding operators
        Mat[] inputMatList = new Mat[BitmapURIRepository.getInstance().getNumImagesSelected()];
        Mat[] yuvRefMat = ColorSpaceOperator.convertRGBToYUV(ImageReader.getInstance().imReadOpenCV(FilenameConstants.INPUT_PREFIX_STRING + (0), ImageFileAttribute.FileType.JPEG));
        for(int i = 0; i < inputMatList.length; i++) {
            Mat lrMat = ImageReader.getInstance().imReadOpenCV(FilenameConstants.INPUT_PREFIX_STRING + (i), ImageFileAttribute.FileType.JPEG);
            Mat[] yuvMat = ColorSpaceOperator.convertRGBToYUV(lrMat);
            inputMatList[i] = yuvMat[ColorSpaceOperator.Y_CHANNEL];
        }

        //extract features
        YangFilter yangFilter = new YangFilter(inputMatList);
        yangFilter.perform();

        SharpnessMeasure.getSharedInstance().measureSharpness(yangFilter.getEdgeMatList());
        SharpnessMeasure.SharpnessResult sharpnessResult = SharpnessMeasure.getSharedInstance().getLatestResult();

        //find appropriate ground-truth
        TestImagesSelector testImagesSelector = new TestImagesSelector(inputMatList, yangFilter.getEdgeMatList(), sharpnessResult);
        testImagesSelector.perform();
        inputMatList = testImagesSelector.getProposedList();

        //remeasure sharpness result without the image ground-truth
        sharpnessResult = SharpnessMeasure.getSharedInstance().measureSharpness(testImagesSelector.getProposedEdgeList());

        //trim the input list from the measured sharpness mean
        inputMatList = SharpnessMeasure.getSharedInstance().trimMatList(inputMatList, sharpnessResult);

        DenoisingOperator denoisingOperator = new DenoisingOperator(inputMatList);
        denoisingOperator.perform();

        int index = 0;
        for(int i = 0; i < BitmapURIRepository.getInstance().getNumImagesSelected(); i++) {
            index = i;
            if(ImageReader.getInstance().doesImageExists(FilenameConstants.INPUT_PREFIX_STRING + i, ImageFileAttribute.FileType.JPEG)) {
                break;
            }
        }
        Log.d(TAG, "First index: " +index);
        LRToHROperator lrToHROperator = new LRToHROperator(ImageReader.getInstance().imReadOpenCV(FilenameConstants.INPUT_PREFIX_STRING + index, ImageFileAttribute.FileType.JPEG));
        lrToHROperator.perform();

        //perform feature matching of LR images against the first image as reference mat.
        inputMatList = denoisingOperator.getResult();
        Mat[] succeedingMatList =new Mat[inputMatList.length - 1];
        for(int i = 1; i < inputMatList.length; i++) {
            succeedingMatList[i - 1] = inputMatList[i];
        }

        //perform affine warping
        AffineWarpingOperator warpingOperator = new AffineWarpingOperator(inputMatList[0], succeedingMatList);
        warpingOperator.perform();
        succeedingMatList = warpingOperator.getWarpedMatList();

        //perform perspective warping
        FeatureMatchingOperator matchingOperator = new FeatureMatchingOperator(inputMatList[0], succeedingMatList);
        matchingOperator.perform();

        LRWarpingOperator perspectiveWarpOperator = new LRWarpingOperator(matchingOperator.getRefKeypoint(), succeedingMatList, matchingOperator.getdMatchesList(), matchingOperator.getLrKeypointsList());
        perspectiveWarpOperator.perform();

        ProgressDialogHandler.getInstance().showDialog("Resizing", "Resizing input images");
        Mat initialMat = ImageOperator.performInterpolation(inputMatList[0], ParameterConfig.getScalingFactor(), Imgproc.INTER_CUBIC);
        Mat[] warpedMatList = perspectiveWarpOperator.getWarpedMatList();
        Mat[] combinedMatList = new Mat[warpedMatList.length + 1];
        combinedMatList[0] = initialMat;
        for(int i = 1; i < combinedMatList.length; i++) {
            combinedMatList[i] = ImageOperator.performInterpolation(warpedMatList[i - 1], ParameterConfig.getScalingFactor(), Imgproc.INTER_CUBIC);
        }
        ProgressDialogHandler.getInstance().hideDialog();

        /*ProgressDialogHandler.getInstance().showDialog("Resizing", "Resizing input images");
        Mat initialMat = ImageOperator.performInterpolation(inputMatList[0], ParameterConfig.getScalingFactor(), Imgproc.INTER_CUBIC);
        Mat[] warpedMatList = ProcessedImageRepo.getSharedInstance().getWarpedMatList();
        Mat[] combinedMatList = new Mat[warpedMatList.length + 1];
        DisplacementValue[] displacementValues = opticalFlowZeroFillOperator.getDisplacementValues();

        combinedMatList[0] = initialMat;
        for(int i = 1; i < combinedMatList.length; i++) {
           //resize warp list by zero-fill and displacement
            combinedMatList[i] = ImageOperator.performZeroFill(warpedMatList[i - 1], ParameterConfig.getScalingFactor(),
                    displacementValues[i - 1].getXPoints(), displacementValues[i - 1].getYPoints());

            ImageWriter.getInstance().saveMatrixToImage(combinedMatList[i], "ZeroFill", "warped_resize_"+i, ImageFileAttribute.FileType.JPEG);
        }*/

        MeanFusionOperator fusionOperator = new MeanFusionOperator(combinedMatList, "Fusing", "Fusing images using mean");
        fusionOperator.perform();

       //release unused warp images
        for(int i = 1; i < combinedMatList.length; i++) {
            combinedMatList[i].release();
        }

        ChannelMergeOperator mergeOperator = new ChannelMergeOperator(fusionOperator.getResult(), yuvRefMat[ColorSpaceOperator.U_CHANNEL], yuvRefMat[ColorSpaceOperator.V_CHANNEL]);
        mergeOperator.perform();

        //deallocate some classes
        ProcessedImageRepo.destroy();
        SharpnessMeasure.destroy();
        ProgressDialogHandler.getInstance().hideDialog();
    }

}
