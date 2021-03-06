// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ui;

import com.intellij.diagnostic.Activity;
import com.intellij.diagnostic.StartUpMeasurer;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.application.ex.ApplicationInfoEx;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfoRt;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.ui.scale.ScaleContext;
import com.intellij.util.ImageLoader;
import com.intellij.util.JBHiDPIScaledImage;
import com.intellij.util.concurrency.NonUrgentExecutor;
import com.intellij.util.ui.ImageUtil;
import com.intellij.util.ui.JBImageIcon;
import com.intellij.util.ui.JBInsets;
import com.intellij.util.ui.StartupUiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.util.Base64;

/**
 * To customize your IDE splash go to YourIdeNameApplicationInfo.xml and edit 'logo' tag. For more information see documentation for
 * the tag attributes in ApplicationInfo.xsd file.
 */
public final class Splash extends Window {
  private static final float JBUI_INIT_SCALE = JBUIScale.scale(1f);

  private final int myWidth;
  private final int myHeight;
  private final int myProgressHeight;
  private final int myProgressY;
  private double myProgress;
  private final Color myProgressColor;
  private int myProgressLastPosition = 0;
  private final Icon myProgressTail;
  private final @Nullable ProgressSlidePainter myProgressSlidePainter;
  private final Image myImage;

  public Splash(@NotNull ApplicationInfoEx info) {
    super(null);

    myProgressSlidePainter = info.getProgressSlides().isEmpty() ? null : new ProgressSlidePainter(info);
    myProgressHeight = uiScale(info.getProgressHeight());
    myProgressY = uiScale(info.getProgressY());

    myProgressTail = getProgressTailIcon(info);

    setFocusableWindowState(false);

    myImage = loadImage(info.getSplashImageUrl());
    myWidth = myImage.getWidth(null);
    myHeight = myImage.getHeight(null);
    long rgba = info.getProgressColor();
    //noinspection UseJBColor
    myProgressColor = rgba == -1 ? null : new Color((int)rgba, rgba > 0xffffff);

    setAutoRequestFocus(false);
    setSize(new Dimension(myWidth, myHeight));
    setLocationInTheCenterOfScreen(this);
  }

  private static @Nullable Icon getProgressTailIcon(@NotNull ApplicationInfoEx info) {
    String progressTailIconName = info.getProgressTailIcon();
    Icon progressTail = null;
    if (progressTailIconName != null) {
      try {
        int flags = ImageLoader.USE_SVG | ImageLoader.ALLOW_FLOAT_SCALING;
        if (StartupUiUtil.isUnderDarcula()) {
          flags |= ImageLoader.USE_CACHE;
        }
        Image image = ImageLoader.loadFromUrl(Splash.class.getResource(progressTailIconName).toString(), null, flags, null, ScaleContext.create());
        if (image != null) {
          progressTail = new JBImageIcon(image);
        }
      }
      catch (Exception ignore) {
      }
    }
    return progressTail;
  }

  public void initAndShow(boolean visible) {
    if (myProgressSlidePainter != null) {
      myProgressSlidePainter.startPreloading();
    }
    StartUpMeasurer.addInstantEvent("splash shown");
    Activity activity = StartUpMeasurer.startActivity("splash set visible");
    setVisible(visible);
    activity.end();
    if (visible) {
      paint(getGraphics());
      toFront();
    }
  }

  @Override
  public void dispose() {
    super.dispose();
  }

  private static @NotNull Image loadImage(@NotNull String path) {
    float scale = JBUIScale.sysScale();
    if (isCacheNeeded(scale)) {
      var image = loadImageFromCache(path, scale);
      if (image != null) {
        return image;
      }

      cacheAsync(path);
    }

    Image result = doLoadImage(path);
    if (result == null) {
      throw new IllegalStateException("Cannot find image: " + path);
    }
    return result;
  }

  private static void cacheAsync(@NotNull String url) {
    // Don't use already loaded image to avoid oom
    NonUrgentExecutor.getInstance().execute(() -> {
      var cacheFile = getCacheFile(url, JBUIScale.sysScale());
      if (cacheFile == null) {
        return;
      }
      var image = doLoadImage(url);
      if (image != null) {
        saveImage(cacheFile, FileUtilRt.getExtension(url), image);
      }
    });
  }

  private static boolean isCacheNeeded(float scale) {
    return scale != 1 && scale != 2;
  }

  static @Nullable Image doLoadImage(@NotNull String path) {
    return ImageLoader.load(path, null, Splash.class, null, ImageLoader.ALLOW_FLOAT_SCALING, ScaleContext.create(), !path.endsWith(".svg"));
  }

  @Override
  public void paint(Graphics g) {
    if (myProgress < 0.10 || myProgressSlidePainter == null) {
      StartupUiUtil.drawImage(g, myImage, 0, 0, null);
    }
    else {
      paintProgress(g);
    }
  }

  private static void setLocationInTheCenterOfScreen(@NotNull Window window) {
    GraphicsConfiguration graphicsConfiguration = window.getGraphicsConfiguration();
    Rectangle bounds = graphicsConfiguration.getBounds();
    if (SystemInfoRt.isWindows) {
      JBInsets.removeFrom(bounds, ScreenUtil.getScreenInsets(graphicsConfiguration));
    }
    window.setLocation(StartupUiUtil.getCenterPoint(bounds, window.getSize()));
  }

  public void showProgress(double progress) {
    if (myProgressColor == null) {
      return;
    }

    if (((progress - myProgress) > 0.01) || (progress > 0.99)) {
      myProgress = progress;
      Graphics graphics = getGraphics();
      // not yet initialized
      if (graphics != null) {
        paintProgress(graphics);
      }
    }
  }

  private void paintProgress(@Nullable Graphics g) {
    if (g == null) {
      return;
    }

    if (myProgressSlidePainter != null) {
      myProgressSlidePainter.paintSlides(g, myProgress);
    }

    Color progressColor = myProgressColor;
    if (progressColor == null) {
      return;
    }

    int progressWidth = (int)(myWidth * myProgress);
    int currentWidth = progressWidth - myProgressLastPosition;
    if (currentWidth == 0) {
      return;
    }

    g.setColor(progressColor);
    int y = myProgressSlidePainter == null ? myProgressY : myHeight - myProgressHeight;
    g.fillRect(myProgressLastPosition, y, currentWidth, myProgressHeight);
    if (myProgressTail != null) {
      int tx = (int)(currentWidth - (myProgressTail.getIconWidth() / JBUI_INIT_SCALE / 2f * JBUI_INIT_SCALE));
      int ty = (int)(myProgressY - (myProgressTail.getIconHeight() - myProgressHeight) / JBUI_INIT_SCALE / 2f * JBUI_INIT_SCALE);
      myProgressTail.paintIcon(this, g, tx, ty);
    }
    myProgressLastPosition = progressWidth;
  }

  private static int uiScale(int i) {
    return (int)(i * JBUI_INIT_SCALE);
  }

   private static void saveImage(@NotNull Path file, String extension, @NotNull Image image) {
     try {
       var tmp = file.resolve(file.toString() + ".tmp" + System.currentTimeMillis());
       Files.createDirectories(tmp.getParent());
       try {
         ImageIO.write(ImageUtil.toBufferedImage(image), extension, tmp.toFile());
         try {
           Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
         }
         catch (AtomicMoveNotSupportedException e) {
           Files.move(tmp, file);
         }
       }
       finally {
         Files.deleteIfExists(tmp);
       }
     }
     catch (Throwable ignored) {
     }
   }

   private static @Nullable Image loadImageFromCache(@NotNull String path, float scale) {
     var file = getCacheFile(path, scale);
     if (file == null) {
       return null;
     }

     try {
       if (!Files.isRegularFile(file)) {
         return null;
       }
       Image image = ImageIO.read(file.toFile());
       if (StartupUiUtil.isJreHiDPI()) {
         int w = image.getWidth(ImageLoader.ourComponent);
         int h = image.getHeight(ImageLoader.ourComponent);
         image = new JBHiDPIScaledImage(image, w / (double)scale, h / (double)scale, BufferedImage.TYPE_INT_ARGB);
       }
       return image;
     }
     catch (IOException e) {
       // don't use `error`, because it can crash application
       Logger.getInstance(Splash.class).warn("Failed to load splash image", e);
     }
     return null;
   }

   private static @Nullable Path getCacheFile(@NotNull String path, float scale) {
     try {
       var d = MessageDigest.getInstance("SHA-256", Security.getProvider("SUN"));
       // cache version
       int dotIndex = path.lastIndexOf('.');
       d.update((dotIndex < 0 ? path : path.substring(0, dotIndex)).getBytes(StandardCharsets.UTF_8));
       var encodedDigest = Base64.getUrlEncoder().encodeToString(d.digest());
       return Paths.get(PathManager.getSystemPath(), "splashSlides").resolve(encodedDigest + '.' + scale + '.' + (dotIndex < 0 ? "" : path.substring(dotIndex)));
     }
     catch (NoSuchAlgorithmException e) {
       return null;
     }
   }
}
