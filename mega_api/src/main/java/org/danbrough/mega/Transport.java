/*******************************************************************************
 * Copyright (c) 2013 Dan Brough dan@danbrough.org. All rights reserved. 
 * This program and the accompanying materials are made available under the 
 * terms of the GNU Public License v3.0 which accompanies this distribution, 
 * and is available at http://www.gnu.org/licenses/gpl.html
 * 
 ******************************************************************************/
package org.danbrough.mega;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedList;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

public class Transport {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory
      .getLogger(Transport.class.getSimpleName());

  static String userAgent = "MegaJAVA";

  static {
    userAgent = MegaProperties.getInstance().getUserAgent();
  }

  private boolean running = false;

  LinkedList<ApiRequest> requestQueue = new LinkedList<ApiRequest>();

  public Transport() {
    super();
  }

  public synchronized void start() {
    log.info("start()");
    if (running)
      return;
    running = true;

    Thread workerThread = new Thread() {
      @Override
      public void run() {
        workLoop();
        log.debug("workLoop() finished");
        Transport.this.running = false;
      }
    };
    workerThread.start();
  }

  public synchronized void stop() {
    log.info("stop()");
    if (!running)
      return;
    running = false;
    synchronized (requestQueue) {
      requestQueue.notify();
    }
  }

  protected void workLoop() {

    while (true) {
      if (requestQueue.isEmpty() && running) {
        synchronized (requestQueue) {
          if (requestQueue.isEmpty() && running) {
            try {
              requestQueue.wait();
            } catch (InterruptedException e) {
              continue;
            }
          }
        }
      }

      if (!running)
        return;

      if (!requestQueue.isEmpty()) {
        ApiRequest request = null;
        synchronized (requestQueue) {
          request = requestQueue.getFirst();
        }
        if (request != null && running) {

          try {
            postRequest(request);
          } catch (IOException e) {
            log.error(e.getMessage(), e);
            request.onError(e);
          }

          synchronized (requestQueue) {
            requestQueue.removeFirst();
          }
        }
      }
    }
  }

  public void queueRequest(ApiRequest request) {
    log.debug("queueRequest() request: {} running: {}", request, running);
    synchronized (requestQueue) {
      requestQueue.addLast(request);
      requestQueue.notify();
    }
  }

  protected void postRequest(ApiRequest request) throws IOException {
    log.debug("postRequest() {}", request);

    String url = request.getURL();

    log.debug("url: {}", url);

    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
    conn.setDoOutput(true);
    conn.setDoInput(true);
    conn.setUseCaches(false);
    conn.setRequestProperty("User-Agent", userAgent);
    conn.setRequestProperty("Content-Type", "application/json");
    conn.setAllowUserInteraction(false);
    conn.setRequestMethod("POST");

    String requestData = '[' + request.getRequestData().toString() + ']';
    log.debug("requestData {}", requestData);
    OutputStreamWriter output = new OutputStreamWriter(conn.getOutputStream());
    output.write(requestData);
    output.close();

    BufferedReader input = new BufferedReader(new InputStreamReader(
        conn.getInputStream()));
    StringBuffer responseData = new StringBuffer();
    String s = null;
    while ((s = input.readLine()) != null)
      responseData.append(s);
    input.close();

    String response = responseData.substring(1, responseData.length() - 1);

    Object o;
    try {
      o = new JSONTokener(response).nextValue();
    } catch (JSONException e) {
      throw new IOException(e.getMessage());
    }

    if (o instanceof Integer) {
      request.onError((Integer) o);
    } else {
      request.onResponse((JSONObject) o);
    }

  }

  public boolean getRequestsPending() {
    return !requestQueue.isEmpty();
  }

}