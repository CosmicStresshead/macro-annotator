// TODO: later save greyscale images to save time on recurrent runs

def preamble = """Macro-annotation Script v0.1
Written by Ben Smylie (bensmylie@gmail.com) 2019

Transfers annotations to appropriate scale and position on the associated macro image for macrodissection.
Certain limitations within the available OpenCV/JavaCV API mean some functions had to be written manually;
this has the effect of making the script rather slow (on average about 4 minutes).

"""

println(preamble)

/////////////
// Imports //
/////////////

import qupath.lib.regions.RegionRequest
import qupath.opencv.tools.OpenCVTools

import org.bytedeco.javacv.*
import org.bytedeco.javacpp.*;
import org.bytedeco.javacpp.indexer.FloatIndexer;

import org.bytedeco.opencv.opencv_calib3d.*;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_imgcodecs.*
import org.bytedeco.opencv.opencv_imgproc.*;
import org.bytedeco.opencv.opencv_objdetect.*;

import static org.bytedeco.opencv.global.opencv_calib3d.*;
import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgcodecs.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;
import static org.bytedeco.opencv.global.opencv_objdetect.*; 

import org.opencv.imgproc.Imgproc;

import java.awt.image.BufferedImage
import javax.imageio.ImageIO

import javax.imageio.*
import java.awt.*
import java.io.*
import ij.*

import org.opencv.core.CvType

println("Imports OK\n")



///////////////////////////////
// SETTINGS - Edit only this //
///////////////////////////////

// Detection take-in value for finding macro extraction within H&E image
def takeIn = 150 // integer, increase this in increments of 50 if getting out-of-bounds errors
def lineThickness = 6 // integer
def lineColour = Color.black

def outPath = "c:\\QP\\"
def outFilename = "macro_out.png"

def downsample = 64.0



////////////////////
// QuPath Objects //
////////////////////

qp = getQuPath()
server = getCurrentServer()

if (server == null) {
    println("No image open!");
}

viewer = getCurrentViewer()
annotations = getAnnotationObjects()

if (annotations.size() < 1) {
    println("No annotations found!");
}

imageList = server.getAssociatedImageList()

if ("macro" in imageList) {
    macro_orig = server.getAssociatedImage("macro");
} else {
    println("Macro not found in image list!");
}



//////////////////////
// HELPER FUNCTIONS //
//////////////////////

public boolean isBetween(ArrayList arr, ArrayList lower, ArrayList upper) {
    def result = true
    
    for(i=0; i<arr.size(); ++i) {
        if (arr[i] < lower[i] || arr[i] > upper[i]) { result = false }
    }
    
    return result
}


public ArrayList findInnerBox(BufferedImage img, int minLength, colour=[4, 255, 4], variance=15)
{
    // define boundaries
    width = img.getWidth()
    height = img.getHeight()
    
    // colour range
    def r_target, g_target, b_target;
    (r_target, g_target, b_target) = colour;
    def upper = [Math.min(r_target+variance, 255), Math.min(g_target+variance, 255), Math.min(b_target+variance, 255)]
    def lower = [Math.max(r_target-variance, 0), Math.max(g_target-variance, 0), Math.max(b_target-variance, 0)]
    
    // line detections
    def verticals = []
    def horizontals = []
    
    // main loop verticals
    for (int x=0; x<width; ++x)
    { // start x loop
        def chain = 0;        
        for (int y=0; y<height; ++y)
        {
            int rgb = img.getRGB(x, y);
            int r = (rgb >> 16) & 0xFF
            int g = (rgb >> 8) & 0xFF
            int b = (rgb & 0xFF)
            if (isBetween([r, g, b], lower, upper))
            {
                chain += 1;      
                if (chain > minLength)
                    break;
            }
            else
                chain = 0;
        }
        // if a chain was found, add the column number (x) to verticals list
        if (chain >= minLength && !(x in verticals))
            verticals.add(x)        
    } // end x loop

    // main loop horizontals
    for (int y=0; y<height; ++y)
    { // start x loop
    
        def chain = 0;        
        
        for (int x=0; x<width; ++x)
        { // start y loop
                                
            int rgb = img.getRGB(x, y);
            int r = (rgb >> 16) & 0xFF
            int g = (rgb >> 8) & 0xFF
            int b = (rgb & 0xFF)
            
            if (isBetween([r, g, b], lower, upper))
            {
                chain += 1;      
                if (chain > minLength)
                    break;
            }
            else
                chain = 0;      
        } // end y loop
        
        // if a chain was found, add the column number (x) to verticals list
        if (chain >= minLength && !(y in horizontals))
            horizontals.add(y)
    } // end x loop

    for (i=0; i<verticals.size()-1; ++i) {
        if(verticals[i+1] - verticals[i] > 5)
            verticals = [verticals[i], verticals[i+1]]
    }
    
    for (i=0; i<horizontals.size()-1; ++i) {
        if(horizontals[i+1] - horizontals[i] > 5)
            horizontals = [horizontals[i], horizontals[i+1]]
    }
    
    return [verticals[0]+1, verticals[1], horizontals[0]+1, horizontals[1]]   
}


public rescaleBufferedImage(BufferedImage src, float scale) {

    oldwidth = src.getWidth()
    oldHeight = src.getHeight()
    newWidth = (int)(oldwidth * scale)
    newHeight = (int)(oldHeight * scale)
    
    def bi = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
    Graphics2D g2d = bi.createGraphics();
    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); //produces a balanced resizing (fast and decent quality)
    g2d.drawImage(src, 0, 0, newWidth, newHeight, null);
    g2d.dispose();
    return bi;
}


public void saveMat(Mat img, filepath) {
    bImg = OpenCVTools.matToBufferedImage(img)
    ImageIO.write(bImg, "png", new File(filepath))
}


public void saveBufferedImage(BufferedImage bImg, filename) {
    ImageIO.write(bImg, "png", new File(filename))
}


public Mat imageToMatGrey(BufferedImage bImg) {

    int rows = bImg.getWidth();
    int cols = bImg.getHeight();
    int type = CvType.CV_8UC1;
    
    Mat newMat = new Mat(rows,cols,type);
    
    ind = newMat.createIndexer()

    for (int r=0; r < rows; r++) {
        for(int c=0; c < cols; c++)
            ind.put(r, c, bImg.getRGB(r, c));
    }

    return newMat
}


public BufferedImage RGB2Grey(BufferedImage img) {

    println(" Converting to greyscale (slow)")
    def width = img.getWidth()
    def height = img.getHeight()
    def greyImg = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
    println("  0%")
    for (int x = 0; x < width; ++x)
    {
        for (int y = 0; y < height; ++y)
        {
            int rgb = img.getRGB(x, y);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = (rgb & 0xFF);

            // Normalize and gamma correct:
            float rr = Math.pow(r / 255.0, 2.2);
            float gg = Math.pow(g / 255.0, 2.2);
            float bb = Math.pow(b / 255.0, 2.2);

            // Calculate luminance:
            float lum = 0.2126 * rr + 0.7152 * gg + 0.0722 * bb;
    
            // Gamma compand and rescale to byte range:
            int grayLevel = (int) (255.0 * Math.pow(lum, 1.0 / 2.2));
            int gray = (grayLevel << 16) + (grayLevel << 8) + grayLevel; 
            greyImg.setRGB(x, y, gray);
        }
        // Insert progress report here (percent done in 25% increments)
        if (x == (int)(width/4)) { println("  25%") }
        else if (x == (int)(width/2)) { println("  50%") }
        else if (x == (int)(3*width/4)) { println("  75%") }
        else if (x == width-1) { print("  100%") }
    }
    return greyImg
}


public BufferedImage copyImage(BufferedImage source) {
    BufferedImage b = new BufferedImage(source.getWidth(), source.getHeight(), source.getType());
    Graphics g = b.getGraphics();
    g.drawImage(source, 0, 0, null);
    g.dispose();
    return b;
}


static def getWholeRegion(server, downsample, annotate=true, colour=lineColour, thickness=lineThickness) {
    def request = RegionRequest.createInstance(server, downsample)
    def img = server.readBufferedImage(request)
    def g2d = img.createGraphics()
    g2d.setColor(colour)
    g2d.scale(1.0/downsample, 1.0/downsample)
    g2d.setStroke(new BasicStroke((float)(thickness * downsample)))
    getAnnotationObjects().each { g2d.draw(it.getROI().getShape()) } 
    g2d.dispose()
    return img
}

////////////////////
// Process images //
////////////////////


// Macro
println("Macro:")
println(" Loading...")
macro_orig = server.getAssociatedImage("macro")
//macroFile = new File("c:\\QP\\macro.png")
//macro_orig = ImageIO.read(macroFile)
println(" Scaling...")
macro_orig_scaled = rescaleBufferedImage(macro_orig, 2.5)
// create a copy to work with
macro = copyImage(macro_orig_scaled)

// detect ROI
println(" Finding ROI...");
def lft, rgt, top, bot;
(lft, rgt, top, bot) = findInnerBox(macro, 20, [4, 256, 4], 15)
roiX = lft
roiY = top
roiWidth = rgt - lft
roiHeight = bot - top
// convert to greyscale mat
macro_gr = RGB2Grey(macro)
macro_gr = imageToMatGrey(macro)

// Source (SVS)
println("")
println("Whole slide image:")
println(" Loading...")
source_orig = getWholeRegion(server, downsample, true, lineColour, lineThickness)
//sourceFile = new File("c:\\QP\\whole.png")
//source_orig = ImageIO.read(sourceFile)
// create a copy to work with
source = copyImage(source_orig)
// reduce by takeIn value
println(" Preparing image for template matching...")
source = source.getSubimage(takeIn, takeIn, source.getWidth()-(takeIn*2), source.getHeight()-(takeIn*2))
// convert to greyscale
source_gr = RGB2Grey(source)
source_gr = imageToMatGrey(source)



///////////////////////
// Template matching //
///////////////////////
println("");
println("Template matching:")
def size = new Size(macro_gr.cols()-source_gr.cols()+1, macro_gr.rows()-source_gr.rows()+1)
def matchMat = new Mat(size, CvType.CV_32FC1);

matchTemplate(macro_gr, source_gr, matchMat, TM_CCORR_NORMED);

def minVal = new DoublePointer()
def maxVal = new DoublePointer()

def minLoc = new Point()
def maxLoc = new Point()

minMaxLoc(matchMat, minVal, maxVal, minLoc, maxLoc, null)
println(" Template matching done.\n")

// use these coordinates maxLoc.x() and maxLoc.y() to determine offset
def offsetX = maxLoc.x() - roiY
def offsetY = maxLoc.y() - roiX

// extract area from macro corresponding to alignment of H&E
// correcting for Mat detection X & Y being swapped

finalY = maxLoc.x() - takeIn
finalX = maxLoc.y() - takeIn

// Extract the adjusted ROI for annotation
println("Extracting ROI from scaled macro image...\n");
finalExtract = macro_orig_scaled.getSubimage(finalX, finalY, source.getWidth()+(takeIn*2), source.getHeight()+(takeIn*2))
//finalExtract = drawCircle(macro_orig_scaled, Color.red, 10, finalX, finalY)

// Apply annotation to extracted region
println("Applying annotations...\n");
def g2d = finalExtract.createGraphics()
g2d.setColor(lineColour)
g2d.scale(1.0/downsample, 1.0/downsample)
g2d.setStroke(new java.awt.BasicStroke((float)(lineThickness * downsample)))
getAnnotationObjects().each { g2d.draw(it.getROI().getShape()) } 
g2d.dispose()

// Reinsert region
g2d = macro_orig_scaled.createGraphics()
g2d.drawImage(finalExtract, finalX, finalY, null)
g2d.dispose()

// Save output image
println("Saving final image to " + outPath + outFilename);
saveBufferedImage(macro_orig_scaled, outPath + outFilename)

print("Finished")