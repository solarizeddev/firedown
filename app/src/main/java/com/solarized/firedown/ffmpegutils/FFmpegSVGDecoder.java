package com.solarized.firedown.ffmpegutils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.util.Log;

import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;
import com.solarized.firedown.App;
import com.solarized.firedown.R;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class FFmpegSVGDecoder {

    private static final String TAG = FFmpegSVGDecoder.class.getName();

    private final int targetWidth;

    private final int targetHeight;

    private ByteArrayOutputStream outputStream;

    private float svgWidth;

    private float svgHeight;

    public FFmpegSVGDecoder(){
        outputStream = new ByteArrayOutputStream();
        targetWidth = App.getAppContext().getResources().getDimensionPixelOffset(R.dimen.list_svg_width);
        targetHeight = App.getAppContext().getResources().getDimensionPixelOffset(R.dimen.list_svg_height);
    }

    private int getDocumentWidth(){
        return (int) svgWidth;
    }

    private int getDocumentHeight(){
        return (int) svgHeight;
    }

    private void decodeClose(){
        try{
            if(outputStream != null)
                outputStream.close();
        }catch(IOException e){
            Log.e(TAG, "decodeClose", e);
        }
        outputStream = null;
    }

    private Bitmap decodeData(byte[] buffer){

        Bitmap bitmap = null;

        String svgString = null;

        //Log.d(TAG, "decodeData pre: " + new String(buffer));

        try  {

            outputStream.write(buffer);

            svgString = outputStream.toString();

            if(!svgString.trim().endsWith("</svg>")){
                Log.w(TAG, "decodeData recevied partial svg");
                return null;
            }

            Log.d(TAG, "decodeData post: " + outputStream.toString());

            SVG svg = SVG.getFromString(svgString);

            if (svg.getDocumentWidth() != -1) {

                svgWidth = svg.getDocumentWidth();

                svgHeight = svg.getDocumentHeight();

                svg.setDocumentWidth("100%");

                svg.setDocumentHeight("100%");

                bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);

                Canvas bmcanvas = new Canvas(bitmap);

                // Clear background to white
                bmcanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

                // Render our document onto our canvas
                svg.renderToCanvas(bmcanvas);

            }


        } catch (IOException | SVGParseException | NullPointerException e) {
            Log.e(TAG, "decodeDataError: " + svgString, e);
        }

        return bitmap;

    }
}
