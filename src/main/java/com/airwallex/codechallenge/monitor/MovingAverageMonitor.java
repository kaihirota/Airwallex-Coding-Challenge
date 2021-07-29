package com.airwallex.codechallenge.monitor;

import com.airwallex.codechallenge.monitor.alert.Alert;
import com.airwallex.codechallenge.monitor.alert.SpotChangeAlert;
import com.airwallex.codechallenge.input.CurrencyConversionRate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javatuples.Pair;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Optional;
import java.util.PriorityQueue;

public class MovingAverageMonitor extends Monitor {
  private static final Class<SpotChangeAlert> alertType = SpotChangeAlert.class;
  private static final Logger logger = LogManager.getLogger();
  private static final Hashtable<String, PriorityQueue<Pair<Instant, CurrencyConversionRate>>> queues = new Hashtable<>();
  private static final Hashtable<String, Pair<Double, Integer>> queueInfo = new Hashtable<>();
  private static int queueSize;
  private static float pctChangeThreshold;
  private static MovingAverageMonitor instance = null;
  private static CurrencyConversionRate lastData = null;

  private MovingAverageMonitor(int windowSize, float threshold) {
    queueSize = windowSize;
    pctChangeThreshold = threshold;
  }

  public static MovingAverageMonitor create(int windowSize, float pctChangeThreshold) {
    if (instance == null) {
      instance = new MovingAverageMonitor(windowSize, pctChangeThreshold);
    }
    return instance;
  }

  @Override
  public Class<SpotChangeAlert> getAlertType() {
    return alertType;
  }

  @Override
  public Monitor processRow(CurrencyConversionRate conversionRate) {
    lastData = conversionRate;
    String currencyPair = conversionRate.getCurrencyPair();

    // get or create priority queue for the currency pair
    PriorityQueue<Pair<Instant, CurrencyConversionRate>> q;
    q = queues.getOrDefault(currencyPair, new PriorityQueue<>(queueSize));

    // get cumulative sum of the last n entries, and current queue size
    Pair<Double, Integer> currencyInfo = queueInfo.getOrDefault(currencyPair, Pair.with(0.0, 0));
    double cumsum = currencyInfo.getValue0();
    int currentQueueSize = currencyInfo.getValue1();

    // if queue is full, remove one item and subtract its value from cumsum
    // if not full, update current queue size
    if (currentQueueSize == queueSize) {
      Pair<Instant, CurrencyConversionRate> expiredData = q.remove();
      cumsum -= expiredData.getValue1().getRate();
    } else {
      currentQueueSize += 1;
    }

    // update queue and queueInfo
    q.add(Pair.with(conversionRate.getTimestamp(), conversionRate));
    queues.put(currencyPair, q);
    queueInfo.put(currencyPair, Pair.with(cumsum + conversionRate.getRate(), currentQueueSize));

    return this;
  }

  @Override
  public ArrayList<Alert> getAlertsIfAny() {
    ArrayList<Alert> alerts = new ArrayList<>();
    Method[] methods = this.getClass().getDeclaredMethods();
    for(Method method : methods){
      if (Modifier.isPublic(method.getModifiers()) && method.getName().startsWith("checkAlert")) {
        try {
          Optional<Alert> maybeAlert = (Optional<Alert>) method.invoke(this);
          maybeAlert.ifPresent(alerts::add);
        } catch (IllegalAccessException | InvocationTargetException e) {
          logger.error(e);
        }
      }
    }
    return alerts;
  }

  public Optional<Alert> checkAlertMovingAverage() {
    if (lastData == null) return Optional.empty();

    Optional<Double> movingAverageMaybe = this.getCurrentMovingAverage(lastData.getCurrencyPair());

    if (!movingAverageMaybe.isPresent()) return Optional.empty();

    double movingAverage = movingAverageMaybe.get();
    double pctChange = (lastData.getRate() - movingAverage) / movingAverage;

    if (pctChange < pctChangeThreshold) return Optional.empty();
    else return Optional.of(new SpotChangeAlert(lastData, pctChange, movingAverage, pctChangeThreshold));
  }

  public Optional<Double> getCurrentMovingAverage(String currencyPair) {
    Pair<Double, Integer> currencyInfo = queueInfo.getOrDefault(currencyPair, null);
    return (currencyInfo != null) ? Optional.of(currencyInfo.getValue0() / currencyInfo.getValue1()) : Optional.empty();
  }
}