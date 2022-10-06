/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jackpal.androidterm.emulatorview;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Environment;
import android.text.TextPaint;

import java.io.File;

class PaintRenderer extends BaseTextRenderer {
    private static final String FONTPATH = Environment.getExternalStorageDirectory().getPath() + "/fonts";
    private int mTextLeading = 0;

    @SuppressLint("NewApi")
    public PaintRenderer(int fontSize, ColorScheme scheme, String fontFile, int textLeading) {
        super(scheme);
        mTextPaint = new Paint();
        mTextLeading = textLeading;
        String fontPath;
        if (fontFile == null) {
            fontPath = String.format("%s/%s", FONTPATH, (fontFile != null ? fontFile : "default.ttf"));
        } else {
            fontPath = fontFile;
        }
        File file = new File(fontPath);
        if (file.canRead()) {
            try {
                mTextPaint.setTypeface(Typeface.createFromFile(file));
            } catch (Exception e) {
                mTextPaint.setTypeface(Typeface.MONOSPACE);
            }
        } else {
            mTextPaint.setTypeface(Typeface.MONOSPACE);
        }
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(fontSize);

        mCharHeight = (int) Math.ceil(mTextPaint.getFontSpacing());
        mCharAscent = (int) Math.ceil(mTextPaint.ascent());
        mCharDescent = mCharHeight + mCharAscent;
        mCharHeight += mTextLeading * 2;
        mCharDescent += mTextLeading;
        mCharWidth = mTextPaint.measureText(EXAMPLE_CHAR, 0, 1);
    }

    public void drawTextRun(Canvas canvas, float x, float y, int lineOffset,
                            int runWidth, char[] text, int index, int count,
                            boolean selectionStyle, int textStyle,
                            int cursorOffset, int cursorIndex, int cursorIncr, int cursorWidth, int cursorMode) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        int effect = TextStyle.decodeEffect(textStyle);

        boolean inverse = mReverseVideo ^
                (effect & (TextStyle.fxInverse | TextStyle.fxItalic)) != 0;
        if (inverse) {
            int temp = foreColor;
            foreColor = backColor;
            backColor = temp;
        }

        if (selectionStyle) {
            backColor = TextStyle.ciCursorBackground;
        }

        boolean blink = (effect & TextStyle.fxBlink) != 0;
        if (blink && backColor < 8) {
            backColor += 8;
        }
        if (backColor >= TextStyle.ciColorLength) backColor = TextStyle.ciBackground;
        if (foreColor >= TextStyle.ciColorLength) foreColor = TextStyle.ciForeground;
        mTextPaint.setColor(mPalette[backColor]);
        if ((effect & TextStyle.fxIme) != 0) {
            mTextPaint.setColor(mImePaint.bgColor);
        } else if ((effect & TextStyle.fxImeBackground) != 0) {
            mTextPaint.setColor(mPalette[foreColor]);
        }

        float left = x + lineOffset * mCharWidth;
        float textWidth = mTextPaint.measureText(new String(text));
        canvas.drawRect(left, y + mCharAscent - mCharDescent - mTextLeading,
                left + textWidth, y,
                mTextPaint);

        boolean cursorVisible = lineOffset <= cursorOffset && cursorOffset < (lineOffset + runWidth);
        float cursorX = 0;
        if (cursorVisible) {
            cursorX = x + cursorOffset * mCharWidth;
            if (mCursorHeightMode == 3) {
                drawCursorImp(canvas, cursorX, y, cursorWidth * mCharWidth, mCharHeight, cursorMode);
                cursorVisible = false;
            }
        }

        boolean invisible = (effect & TextStyle.fxInvisible) != 0;
        if (!invisible) {
            boolean bold = (effect & TextStyle.fxBold) != 0;
            boolean underline = (effect & TextStyle.fxUnderline) != 0;
            if (bold) {
                mTextPaint.setFakeBoldText(true);
            }
            if (underline) {
                mTextPaint.setUnderlineText(true);
            }
            int textPaintColor;
            if (foreColor < 8 && bold) {
                // In 16-color mode, bold also implies bright foreground colors
                textPaintColor = mPalette[foreColor + 8];
            } else {
                textPaintColor = mPalette[foreColor];
            }
            mTextPaint.setColor(textPaintColor);
            if ((effect & TextStyle.fxIme) != 0) {
                mTextPaint.setColor(mImePaint.getColor());
            }

            float textOriginY = y - mCharDescent;

            if (cursorVisible) {
                if (mCursorHeightMode == 2) {
                    canvas.drawText(text, index, count, left, textOriginY, mTextPaint);
                    drawCursorImp(canvas, cursorX, y, cursorWidth * mCharWidth, mCharHeight, cursorMode);
                    mTextPaint.setColor(mPalette[TextStyle.ciCursorForeground]);
                    canvas.drawText(text, cursorIndex, cursorIncr, cursorX, textOriginY, mTextPaint);
                } else {
                    // Text before cursor
                    int countBeforeCursor = cursorIndex - index;
                    int countAfterCursor = count - (countBeforeCursor + cursorIncr);
                    if (countBeforeCursor > 0) {
                        canvas.drawText(text, index, countBeforeCursor, left, textOriginY, mTextPaint);
                    }
                    // Text at cursor
                    drawCursorImp(canvas, cursorX, y, cursorWidth * mCharWidth, mCharHeight, cursorMode);
                    if (mCursorHeightMode == 0 || mCursorHeightMode == 2) {
                        mTextPaint.setColor(mPalette[TextStyle.ciCursorForeground]);
                    } else if (mCursorHeightMode >= 4) {
                        mTextPaint.setColor(textPaintColor);
                    }
                    canvas.drawText(text, cursorIndex, cursorIncr, cursorX, textOriginY, mTextPaint);
                    // Text after cursor
                    if (countAfterCursor > 0) {
                        mTextPaint.setColor(textPaintColor);
                        canvas.drawText(text, cursorIndex + cursorIncr, countAfterCursor,
                                cursorX + cursorWidth * mCharWidth, textOriginY, mTextPaint);
                    }
                }
            } else {
                canvas.drawText(text, index, count, left, textOriginY, mTextPaint);
            }
            if (bold) {
                mTextPaint.setFakeBoldText(false);
            }
            if (underline) {
                mTextPaint.setUnderlineText(false);
            }
        }
    }

    public int getCharacterHeight() {
        return mCharHeight;
    }

    public float getCharacterWidth() {
        return mCharWidth;
    }

    public float getMeasureText(String text) {
        return mTextPaint.measureText(text);
    }

    public void setImePaint(TextPaint paint) {
        mImePaint = paint;
    }

    public int getTopMargin() {
        return mCharDescent;
    }

    private final Paint mTextPaint;
    private TextPaint mImePaint = null;
    private final float mCharWidth;
    private int mCharHeight;
    private final int mCharAscent;
    private int mCharDescent;
    private static final char[] EXAMPLE_CHAR = {'M'};

}
