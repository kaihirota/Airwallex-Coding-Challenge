package com.airwallex.codechallenge;

import com.airwallex.codechallenge.monitor.Monitor;
import com.airwallex.codechallenge.monitor.MovingAverageMonitor;
import com.airwallex.codechallenge.monitor.alert.Alert;
import com.airwallex.codechallenge.input.CurrencyConversionRate;
import com.airwallex.codechallenge.reader.ConfigReader;
import com.airwallex.codechallenge.reader.jsonreader.JsonReader;
import com.airwallex.codechallenge.writer.jsonwriter.JsonWriter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import org.json.simple.parser.ParseException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Optional;

public class App {
  private static final Logger logger = LogManager.getLogger("App");
  private static int nDataPoints = 0;
  private static final ArrayList<Monitor> MONITORS = new ArrayList<>();

  public static void main(String[] args) {
    // initialize log4j2 config
    Configurator.initialize(null, "src/resources/log4j2.properties");

    if (args.length == 0) {
      throw new IndexOutOfBoundsException("Supply input file as an argument.");
    }
    try {
      // open config file
      Path configDir = Paths.get(System.getProperty("user.dir"), "/src/resources/config.properties");
      ConfigReader config = new ConfigReader(configDir.toString());

      // read parameters
      int windowSize = Integer.parseInt(config.get("moving_average_window"));
      float pctChangeThreshold = Float.parseFloat(config.get("pct_change_threshold"));

      // add data stores here
      MONITORS.add(MovingAverageMonitor.create(windowSize, pctChangeThreshold));

      run(args[0], windowSize, pctChangeThreshold);
      System.exit(0);
    } catch (IOException e) {
      logger.error(e.getMessage());
      System.exit(1);
    }
  }

  public static void run(String inputFile, int windowSize, Float pctChangeThreshold) throws IOException {
    long startTime = System.nanoTime();

    // create output and logs directory if they do not exist
    Path baseDir = Paths.get(System.getProperty("user.dir"));
    File outputDirectory = new File(String.valueOf(Paths.get(baseDir.toString(), "output")));
    if (!outputDirectory.exists()) outputDirectory.mkdir();
    File logDirectory = new File(String.valueOf(Paths.get(baseDir.toString(), "logs")));
    if (!logDirectory.exists()) logDirectory.mkdir();

    // create reader and writer
    JsonReader reader = new JsonReader(inputFile);
    JsonWriter writer = new JsonWriter();

    // log params
    String msg = String.format(
            "Program starting with parameters:\n\tInput File: %s\n\tOutput File: %s\n\tMoving Average Window Size: %d\n\tPercent Change Threshold: %.2f",
            reader.getPath(),
            writer.getPath(),
            windowSize,
            pctChangeThreshold
    );
    logger.debug(msg);

    // while input file has next line, process each data point using a subclass of Monitor, check for alerts, and write alerts if needed
    while (reader.hasNextLine()) {
      Optional<CurrencyConversionRate> conversionRateMaybe = reader.readLine();

      if (conversionRateMaybe.isPresent()) {
        CurrencyConversionRate conversionRate = conversionRateMaybe.get();

        // process each row and check if there are any alerts
        MONITORS.forEach(
                monitor -> {
                  monitor.processRow(conversionRate);
                  monitor.checkAllAlerts().forEach(
                          alert -> {
                            logger.info(alert.toString());
                            writer.writeLine(alert.toJson());
                          });
                });
        nDataPoints++;
      }
    }

    writer.close();

    // performance stats
    logger.debug(
        String.format(
            "%d data points processed in %.6f seconds.",
            nDataPoints, (System.nanoTime() - startTime) / 1_000_000_000.0));
  }
}
