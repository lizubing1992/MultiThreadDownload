package com.aspsine.multithreaddownload.core;

import android.os.Process;
import android.text.TextUtils;

import com.aspsine.multithreaddownload.Constants;
import com.aspsine.multithreaddownload.DownloadException;
import com.aspsine.multithreaddownload.architecture.ConnectTask;
import com.aspsine.multithreaddownload.architecture.DownloadStatus;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;

/**
 * Created by Aspsine on 2015/7/20.
 */
public class ConnectTaskImpl implements ConnectTask {

  private final String mUri;
  private final OnConnectListener mOnConnectListener;

  private volatile int mStatus;

  private volatile long mStartTime;

  public ConnectTaskImpl(String uri, OnConnectListener listener) {
    this.mUri = uri;
    this.mOnConnectListener = listener;
  }

  @Override
  public void pause() {
    mStatus = DownloadStatus.STATUS_PAUSED;
  }

  public void cancel() {
    mStatus = DownloadStatus.STATUS_CANCELED;
  }

  @Override
  public boolean isConnecting() {
    return mStatus == DownloadStatus.STATUS_CONNECTING;
  }

  @Override
  public boolean isConnected() {
    return mStatus == DownloadStatus.STATUS_CONNECTED;
  }

  @Override
  public boolean isPaused() {
    return mStatus == DownloadStatus.STATUS_PAUSED;
  }

  @Override
  public boolean isCanceled() {
    return mStatus == DownloadStatus.STATUS_CANCELED;
  }

  @Override
  public boolean isFailed() {
    return mStatus == DownloadStatus.STATUS_FAILED;
  }

  @Override
  public void run() {
    // 设置为后台线程
    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
    //修改连接中状态
    mStatus = DownloadStatus.STATUS_CONNECTING;
    //回调给调用者
    mOnConnectListener.onConnecting();
    try {
      //执行连接方法
      executeConnection();
    } catch (DownloadException e) {
      handleDownloadException(e);
    }
  }

  /**
   *
   * @throws DownloadException
   */
  private void executeConnection() throws DownloadException {
    mStartTime = System.currentTimeMillis();
    HttpURLConnection httpConnection = null;
    final URL url;
    try {
      url = new URL(mUri);
    } catch (MalformedURLException e) {
      throw new DownloadException(DownloadStatus.STATUS_FAILED, "Bad url.", e);
    }
    try {
      httpConnection = (HttpURLConnection) url.openConnection();
      httpConnection.setConnectTimeout(Constants.HTTP.CONNECT_TIME_OUT);
      httpConnection.setReadTimeout(Constants.HTTP.READ_TIME_OUT);
      httpConnection.setRequestMethod(Constants.HTTP.GET);
      httpConnection.setRequestProperty("Range", "bytes=" + 0 + "-");
      final int responseCode = httpConnection.getResponseCode();
      if (responseCode == HttpURLConnection.HTTP_OK) {
        //后台不支持断点下载,启用单线程下载
        parseResponse(httpConnection, false);
      } else if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
        //后台支持断点下载,启用多线程下载
        parseResponse(httpConnection, true);
      } else {
        throw new DownloadException(DownloadStatus.STATUS_FAILED,
            "UnSupported response code:" + responseCode);
      }
    } catch (ProtocolException e) {
      throw new DownloadException(DownloadStatus.STATUS_FAILED, "Protocol error", e);
    } catch (IOException e) {
      throw new DownloadException(DownloadStatus.STATUS_FAILED, "IO error", e);
    } finally {
      if (httpConnection != null) {
        httpConnection.disconnect();
      }
    }
  }

  private void parseResponse(HttpURLConnection httpConnection, boolean isAcceptRanges)
      throws DownloadException {

    final long length;
    //header获取length
    String contentLength = httpConnection.getHeaderField("Content-Length");
    if (TextUtils.isEmpty(contentLength) || contentLength.equals("0") || contentLength
        .equals("-1")) {
      //判断后台给你length,为null 0,-1,从连接中获取
      length = httpConnection.getContentLength();
    } else {
      //直接转化
      length = Long.parseLong(contentLength);
    }

    if (length <= 0) {
      //抛出异常数据
      throw new DownloadException(DownloadStatus.STATUS_FAILED, "length <= 0");
    }
    //判断是否取消和暂停
    checkCanceledOrPaused();

    //Successful
    mStatus = DownloadStatus.STATUS_CONNECTED;
    //获取时间差
    final long timeDelta = System.currentTimeMillis() - mStartTime;
    //回调给调用者
    mOnConnectListener.onConnected(timeDelta, length, isAcceptRanges);
  }

  private void checkCanceledOrPaused() throws DownloadException {
    if (isCanceled()) {
      // cancel
      throw new DownloadException(DownloadStatus.STATUS_CANCELED, "Connection Canceled!");
    } else if (isPaused()) {
      // paused
      throw new DownloadException(DownloadStatus.STATUS_PAUSED, "Connection Paused!");
    }
  }

  //统一执行对应的异常信息
  private void handleDownloadException(DownloadException e) {
    switch (e.getErrorCode()) {
      case DownloadStatus.STATUS_FAILED:
        synchronized (mOnConnectListener) {
          mStatus = DownloadStatus.STATUS_FAILED;
          mOnConnectListener.onConnectFailed(e);
        }
        break;
      case DownloadStatus.STATUS_PAUSED:
        synchronized (mOnConnectListener) {
          mStatus = DownloadStatus.STATUS_PAUSED;
          mOnConnectListener.onConnectPaused();
        }
        break;
      case DownloadStatus.STATUS_CANCELED:
        synchronized (mOnConnectListener) {
          mStatus = DownloadStatus.STATUS_CANCELED;
          mOnConnectListener.onConnectCanceled();
        }
        break;
      default:
        throw new IllegalArgumentException("Unknown state");
    }
  }
}
