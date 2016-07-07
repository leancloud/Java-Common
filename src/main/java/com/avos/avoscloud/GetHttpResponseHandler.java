package com.avos.avoscloud;

import com.avos.avoscloud.internal.InternalConfigurationController;
import com.avos.avoscloud.okhttp.internal.framed.Header;

public class GetHttpResponseHandler extends AsyncHttpResponseHandler {


  private String absoluteURLString;
  private AVQuery.CachePolicy policy = AVQuery.CachePolicy.IGNORE_CACHE;



  public GetHttpResponseHandler(GenericObjectCallback cb) {
    super(cb);
  }

  public GetHttpResponseHandler(GenericObjectCallback cb, AVQuery.CachePolicy policy,
      String absoluteURLString) {
    this(cb);
    this.policy = policy;
    this.absoluteURLString = absoluteURLString;
  }

  private boolean isNotModifiedStatus(int code) {
    return (code == 304);
  }

  private boolean isUnAuthorize(int code) {
    return (code == 401);
  }

  private void tryLastModifyCache(final String lastModifiedValue) {
    if (getCallback() == null) {
      return;
    }
    if (lastModifiedValue == null) {
      LogUtil.log.d("null last-modified value");
    } else if (PaasClient.getLastModify(absoluteURLString) == null) {
      PaasClient.updateLastModify(absoluteURLString, lastModifiedValue);
    }
    InternalConfigurationController.globalInstance().getCache()
        .get(absoluteURLString, Long.MAX_VALUE, lastModifiedValue, new GenericObjectCallback() {
          @Override
          public void onSuccess(String content, AVException e) {
            getCallback().onSuccess(content, null);
          }

          @Override
          public void onFailure(Throwable error, String content) {
            PaasClient.removeLastModifyForUrl(absoluteURLString);
            getCallback().onFailure(error, content);
          }
        });
  }


  @Override
  public void onSuccess(int statusCode, Header[] headers, byte[] body) {

    String content = AVUtils.stringFromBytes(body);
    if (InternalConfigurationController.globalInstance().getInternalLogger().isDebugEnabled()) {
      LogUtil.avlog.d(content);
    }
    if (isNotModifiedStatus(statusCode)) {
      if (InternalConfigurationController.globalInstance().getInternalLogger().isDebugEnabled()) {
        LogUtil.avlog.i("Last modify matched.");
      }
      String lastModifiedValue = PaasClient.lastModifyFromHeaders(headers);
      tryLastModifyCache(lastModifiedValue);
      return;
    }

    String contentType = AVUtils.extractContentType(headers);
    if (AVUtils.checkResponseType(statusCode, content, contentType, getCallback()))
      return;

    int code = AVErrorUtils.errorCode(content);
    if (code > 0) {
      if (getCallback() != null) {
        getCallback().onFailure(AVErrorUtils.createException(content), content);
      }
      return;
    }

    if (policy != AVQuery.CachePolicy.IGNORE_CACHE && !AVUtils.isBlankString(absoluteURLString)) {
      InternalConfigurationController.globalInstance().getCache()
          .save(absoluteURLString, content, null);
    }

    // if last modify is enabled, cache object.
    if (!AVUtils.isBlankString(absoluteURLString) && PaasClient.isLastModifyEnabled()) {
      String lastModify = PaasClient.lastModifyFromHeaders(headers);
      if (InternalConfigurationController.globalInstance().getCache()
          .save(absoluteURLString, content, lastModify)) {
        PaasClient.updateLastModify(absoluteURLString, lastModify);
      }
    }

    if (getCallback() != null) {
      getCallback().onSuccess(content, null);
    }
    // 在有请求成功的时候，安排一次archiveRequest发送。真正发起请求则是在之后的２分钟
    InternalConfigurationController.globalInstance().getClientConfiguration().afterSuccess();
  }

  @Override
  public void onFailure(int statusCode, Header[] headers, byte[] responseBody, Throwable error) {


    String content = AVUtils.stringFromBytes(responseBody);

    if (isUnAuthorize(statusCode)) {
      LogUtil.avlog.e(content + "\nerror:" + error + " for request:" + absoluteURLString);
    }

    if (InternalConfigurationController.globalInstance().getInternalLogger().isDebugEnabled()) {
      LogUtil.avlog.e(content + "\nerror:" + error);
    }

    String contentType = AVUtils.extractContentType(headers);
    if (AVUtils.checkResponseType(statusCode, content, contentType, getCallback()))
      return;

    if (getCallback() != null) {
      getCallback().onFailure(statusCode, error, content);
    }
  }
}
