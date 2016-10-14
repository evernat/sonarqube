/*
 * SonarQube
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.scanner.sensor;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.fs.InputComponent;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.TextRange;
import org.sonar.api.batch.fs.internal.DefaultInputFile;
import org.sonar.api.batch.measure.Metric;
import org.sonar.api.batch.measure.MetricFinder;
import org.sonar.api.batch.sensor.coverage.internal.DefaultCoverage;
import org.sonar.api.batch.sensor.cpd.internal.DefaultCpdTokens;
import org.sonar.api.batch.sensor.error.AnalysisError;
import org.sonar.api.batch.sensor.highlighting.internal.DefaultHighlighting;
import org.sonar.api.batch.sensor.highlighting.internal.SyntaxHighlightingRule;
import org.sonar.api.batch.sensor.internal.SensorStorage;
import org.sonar.api.batch.sensor.issue.Issue;
import org.sonar.api.batch.sensor.measure.Measure;
import org.sonar.api.batch.sensor.measure.internal.DefaultMeasure;
import org.sonar.api.batch.sensor.symbol.internal.DefaultSymbolTable;
import org.sonar.api.config.Settings;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.utils.KeyValueFormat;
import org.sonar.core.metric.ScannerMetrics;
import org.sonar.duplications.block.Block;
import org.sonar.duplications.internal.pmd.PmdBlockChunker;
import org.sonar.scanner.cpd.deprecated.DefaultCpdBlockIndexer;
import org.sonar.scanner.cpd.index.SonarCpdBlockIndex;
import org.sonar.scanner.index.BatchComponentCache;
import org.sonar.scanner.issue.ModuleIssues;
import org.sonar.scanner.protocol.output.FileStructure;
import org.sonar.scanner.protocol.output.ScannerReport;
import org.sonar.scanner.protocol.output.ScannerReportWriter;
import org.sonar.scanner.report.ReportPublisher;
import org.sonar.scanner.report.ScannerReportUtils;
import org.sonar.scanner.repository.ContextPropertiesCache;
import org.sonar.scanner.scan.measure.MeasureCache;
import org.sonar.scanner.sensor.coverage.CoverageExclusions;

public class DefaultSensorStorage implements SensorStorage {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultSensorStorage.class);

  private static final List<Metric<?>> INTERNAL_METRICS = Arrays.<Metric<?>>asList(
    // Computed by LinesSensor
    CoreMetrics.LINES);

  private static final List<String> DEPRECATED_METRICS_KEYS = Arrays.asList(
    CoreMetrics.DEPENDENCY_MATRIX_KEY,
    CoreMetrics.DIRECTORY_CYCLES_KEY,
    CoreMetrics.DIRECTORY_EDGES_WEIGHT_KEY,
    CoreMetrics.DIRECTORY_FEEDBACK_EDGES_KEY,
    CoreMetrics.DIRECTORY_TANGLE_INDEX_KEY,
    CoreMetrics.DIRECTORY_TANGLES_KEY,
    CoreMetrics.FILE_CYCLES_KEY,
    CoreMetrics.FILE_EDGES_WEIGHT_KEY,
    CoreMetrics.FILE_FEEDBACK_EDGES_KEY,
    CoreMetrics.FILE_TANGLE_INDEX_KEY,
    CoreMetrics.FILE_TANGLES_KEY);

  private final MetricFinder metricFinder;
  private final ModuleIssues moduleIssues;
  private final CoverageExclusions coverageExclusions;
  private final BatchComponentCache componentCache;
  private final ReportPublisher reportPublisher;
  private final MeasureCache measureCache;
  private final SonarCpdBlockIndex index;
  private final ContextPropertiesCache contextPropertiesCache;
  private final Settings settings;
  private final ScannerMetrics scannerMetrics;
  private final Map<Metric<?>, Metric<?>> deprecatedCoverageMetricMapping = new IdentityHashMap<>();
  private final Set<Metric<?>> coverageMetrics = new HashSet<>();
  private final Set<Metric<?>> byLineMetrics = new HashSet<>();

  public DefaultSensorStorage(MetricFinder metricFinder, ModuleIssues moduleIssues,
    Settings settings,
    CoverageExclusions coverageExclusions, BatchComponentCache componentCache, ReportPublisher reportPublisher,
    MeasureCache measureCache, SonarCpdBlockIndex index,
    ContextPropertiesCache contextPropertiesCache, ScannerMetrics scannerMetrics) {
    this.metricFinder = metricFinder;
    this.moduleIssues = moduleIssues;
    this.settings = settings;
    this.coverageExclusions = coverageExclusions;
    this.componentCache = componentCache;
    this.reportPublisher = reportPublisher;
    this.measureCache = measureCache;
    this.index = index;
    this.contextPropertiesCache = contextPropertiesCache;
    this.scannerMetrics = scannerMetrics;

    coverageMetrics.add(CoreMetrics.UNCOVERED_LINES);
    coverageMetrics.add(CoreMetrics.LINES_TO_COVER);
    coverageMetrics.add(CoreMetrics.UNCOVERED_CONDITIONS);
    coverageMetrics.add(CoreMetrics.CONDITIONS_TO_COVER);
    coverageMetrics.add(CoreMetrics.CONDITIONS_BY_LINE);
    coverageMetrics.add(CoreMetrics.COVERED_CONDITIONS_BY_LINE);
    coverageMetrics.add(CoreMetrics.COVERAGE_LINE_HITS_DATA);

    byLineMetrics.add(CoreMetrics.COVERAGE_LINE_HITS_DATA);
    byLineMetrics.add(CoreMetrics.COVERED_CONDITIONS_BY_LINE);
    byLineMetrics.add(CoreMetrics.CONDITIONS_BY_LINE);

    deprecatedCoverageMetricMapping.put(CoreMetrics.IT_COVERAGE, CoreMetrics.COVERAGE);
    deprecatedCoverageMetricMapping.put(CoreMetrics.IT_LINE_COVERAGE, CoreMetrics.LINE_COVERAGE);
    deprecatedCoverageMetricMapping.put(CoreMetrics.IT_BRANCH_COVERAGE, CoreMetrics.BRANCH_COVERAGE);
    deprecatedCoverageMetricMapping.put(CoreMetrics.IT_UNCOVERED_LINES, CoreMetrics.UNCOVERED_LINES);
    deprecatedCoverageMetricMapping.put(CoreMetrics.IT_LINES_TO_COVER, CoreMetrics.LINES_TO_COVER);
    deprecatedCoverageMetricMapping.put(CoreMetrics.IT_UNCOVERED_CONDITIONS, CoreMetrics.UNCOVERED_CONDITIONS);
    deprecatedCoverageMetricMapping.put(CoreMetrics.IT_CONDITIONS_TO_COVER, CoreMetrics.CONDITIONS_TO_COVER);
    deprecatedCoverageMetricMapping.put(CoreMetrics.IT_CONDITIONS_BY_LINE, CoreMetrics.CONDITIONS_BY_LINE);
    deprecatedCoverageMetricMapping.put(CoreMetrics.IT_COVERED_CONDITIONS_BY_LINE, CoreMetrics.COVERED_CONDITIONS_BY_LINE);
    deprecatedCoverageMetricMapping.put(CoreMetrics.IT_COVERAGE_LINE_HITS_DATA, CoreMetrics.COVERAGE_LINE_HITS_DATA);
    deprecatedCoverageMetricMapping.put(CoreMetrics.OVERALL_COVERAGE, CoreMetrics.COVERAGE);
    deprecatedCoverageMetricMapping.put(CoreMetrics.OVERALL_LINE_COVERAGE, CoreMetrics.LINE_COVERAGE);
    deprecatedCoverageMetricMapping.put(CoreMetrics.OVERALL_BRANCH_COVERAGE, CoreMetrics.BRANCH_COVERAGE);
    deprecatedCoverageMetricMapping.put(CoreMetrics.OVERALL_UNCOVERED_LINES, CoreMetrics.UNCOVERED_LINES);
    deprecatedCoverageMetricMapping.put(CoreMetrics.OVERALL_LINES_TO_COVER, CoreMetrics.LINES_TO_COVER);
    deprecatedCoverageMetricMapping.put(CoreMetrics.OVERALL_UNCOVERED_CONDITIONS, CoreMetrics.UNCOVERED_CONDITIONS);
    deprecatedCoverageMetricMapping.put(CoreMetrics.OVERALL_CONDITIONS_TO_COVER, CoreMetrics.CONDITIONS_TO_COVER);
    deprecatedCoverageMetricMapping.put(CoreMetrics.OVERALL_CONDITIONS_BY_LINE, CoreMetrics.CONDITIONS_BY_LINE);
    deprecatedCoverageMetricMapping.put(CoreMetrics.OVERALL_COVERED_CONDITIONS_BY_LINE, CoreMetrics.COVERED_CONDITIONS_BY_LINE);
    deprecatedCoverageMetricMapping.put(CoreMetrics.OVERALL_COVERAGE_LINE_HITS_DATA, CoreMetrics.COVERAGE_LINE_HITS_DATA);
  }

  @Override
  public void store(Measure newMeasure) {
    saveMeasure(newMeasure.inputComponent(), (DefaultMeasure<?>) newMeasure);
  }

  public void saveMeasure(InputComponent component, DefaultMeasure<?> measure) {
    if (isDeprecatedMetric(measure.metric().key())) {
      // Ignore deprecated metrics
      return;
    }
    Metric<?> metric = metricFinder.findByKey(measure.metric().key());
    if (metric == null) {
      throw new UnsupportedOperationException("Unknown metric: " + measure.metric().key());
    }
    if (!scannerMetrics.getMetrics().contains(metric)) {
      throw new UnsupportedOperationException("Metric '" + metric.key() + "' should not be computed by a Sensor");
    }
    if (!measure.isFromCore() && INTERNAL_METRICS.contains(metric)) {
      LOG.debug("Metric " + metric.key() + " is an internal metric computed by SonarQube. Provided value is ignored.");
      return;
    }
    if (deprecatedCoverageMetricMapping.containsKey(metric)) {
      metric = deprecatedCoverageMetricMapping.get(metric);
    }

    if (coverageMetrics.contains(metric)) {
      if (!component.isFile()) {
        throw new UnsupportedOperationException("Saving coverage metric is only allowed on files. Attempt to save '" + metric.key() + "' on '" + component.key() + "'");
      }
      if (coverageExclusions.isExcluded((InputFile) component)) {
        return;
      }
      if (isLineMetrics(metric)) {
        validateCoverageMeasure(measure, (InputFile) component);
        DefaultMeasure<?> previousMeasure = measureCache.byMetric(component.key(), metric.key());
        if (previousMeasure != null) {
          measureCache.put(component.key(), new DefaultMeasure<String>()
            .forMetric((Metric<String>) metric)
            .withValue(KeyValueFormat.format(mergeLineMetric((String) previousMeasure.value(), (String) measure.value()))));
        } else {
          measureCache.put(component.key(), measure);
        }
      } else {
        // Other coverage metrics are all integer values
        DefaultMeasure<?> previousMeasure = measureCache.byMetric(component.key(), metric.key());
        if (previousMeasure != null) {
          measureCache.put(component.key(), new DefaultMeasure<Integer>()
            .forMetric((Metric<Integer>) metric)
            .withValue(Math.max((Integer) previousMeasure.value(), (Integer) measure.value())));
        } else {
          measureCache.put(component.key(), measure);
        }
      }
    } else {
      if (measureCache.contains(component.key(), metric.key())) {
        throw new UnsupportedOperationException("Can not add the same measure twice on " + component + ": " + measure);
      }
      measureCache.put(component.key(), measure);
    }
  }

  /**
   * Merge the two line data measures, keeping max value in case they both contains a value for the same line.
   */
  private Map<Integer, Integer> mergeLineMetric(String value1, String value2) {
    Map<Integer, Integer> data1 = KeyValueFormat.parseIntInt(value1);
    Map<Integer, Integer> data2 = KeyValueFormat.parseIntInt(value2);
    return Stream.of(data1, data2)
      .map(Map::entrySet)
      .flatMap(Collection::stream)
      .collect(
        Collectors.toMap(
          Map.Entry::getKey,
          Map.Entry::getValue,
          Integer::max));
  }

  public boolean isDeprecatedMetric(String metricKey) {
    return DEPRECATED_METRICS_KEYS.contains(metricKey);
  }

  private boolean isLineMetrics(Metric<?> metric) {
    return this.byLineMetrics.contains(metric);
  }

  public void validateCoverageMeasure(DefaultMeasure<?> measure, InputFile inputFile) {
    Map<Integer, Integer> m = KeyValueFormat.parseIntInt((String) measure.value());
    validatePositiveLine(m, inputFile.absolutePath());
    validateMaxLine(m, inputFile);
  }

  private static void validateMaxLine(Map<Integer, Integer> m, InputFile inputFile) {
    int maxLine = inputFile.lines();

    for (int l : m.keySet()) {
      if (l > maxLine) {
        throw new IllegalStateException(String.format("Can't create measure for line %d for file '%s' with %d lines", l, inputFile.absolutePath(), maxLine));
      }
    }
  }

  private static void validatePositiveLine(Map<Integer, Integer> m, String filePath) {
    for (int l : m.keySet()) {
      if (l <= 0) {
        throw new IllegalStateException(String.format("Measure with line %d for file '%s' must be > 0", l, filePath));
      }
    }
  }

  @Override
  public void store(Issue issue) {
    moduleIssues.initAndAddIssue(issue);
  }

  @Override
  public void store(DefaultHighlighting highlighting) {
    ScannerReportWriter writer = reportPublisher.getWriter();
    DefaultInputFile inputFile = (DefaultInputFile) highlighting.inputFile();
    int componentRef = componentCache.get(inputFile).batchId();
    if (writer.hasComponentData(FileStructure.Domain.SYNTAX_HIGHLIGHTINGS, componentRef)) {
      throw new UnsupportedOperationException("Trying to save highlighting twice for the same file is not supported: " + inputFile.absolutePath());
    }
    writer.writeComponentSyntaxHighlighting(componentRef,
      Iterables.transform(highlighting.getSyntaxHighlightingRuleSet(), new BuildSyntaxHighlighting()));
  }

  @Override
  public void store(DefaultSymbolTable symbolTable) {
    ScannerReportWriter writer = reportPublisher.getWriter();
    int componentRef = componentCache.get(symbolTable.inputFile()).batchId();
    if (writer.hasComponentData(FileStructure.Domain.SYMBOLS, componentRef)) {
      throw new UnsupportedOperationException("Trying to save symbol table twice for the same file is not supported: " + symbolTable.inputFile().absolutePath());
    }
    writer.writeComponentSymbols(componentRef,
      Iterables.transform(symbolTable.getReferencesBySymbol().entrySet(), new Function<Map.Entry<TextRange, Set<TextRange>>, ScannerReport.Symbol>() {
        private ScannerReport.Symbol.Builder builder = ScannerReport.Symbol.newBuilder();
        private ScannerReport.TextRange.Builder rangeBuilder = ScannerReport.TextRange.newBuilder();

        @Override
        public ScannerReport.Symbol apply(Map.Entry<TextRange, Set<TextRange>> input) {
          builder.clear();
          rangeBuilder.clear();
          TextRange declaration = input.getKey();
          builder.setDeclaration(rangeBuilder.setStartLine(declaration.start().line())
            .setStartOffset(declaration.start().lineOffset())
            .setEndLine(declaration.end().line())
            .setEndOffset(declaration.end().lineOffset())
            .build());
          for (TextRange reference : input.getValue()) {
            builder.addReference(rangeBuilder.setStartLine(reference.start().line())
              .setStartOffset(reference.start().lineOffset())
              .setEndLine(reference.end().line())
              .setEndOffset(reference.end().lineOffset())
              .build());
          }
          return builder.build();
        }

      }));
  }

  @Override
  public void store(DefaultCoverage defaultCoverage) {
    if (coverageExclusions.isExcluded(defaultCoverage.inputFile())) {
      return;
    }
    if (defaultCoverage.linesToCover() > 0) {
      saveMeasure(defaultCoverage.inputFile(), new DefaultMeasure<Integer>().forMetric(CoreMetrics.LINES_TO_COVER).withValue(defaultCoverage.linesToCover()));
      saveMeasure(defaultCoverage.inputFile(),
        new DefaultMeasure<Integer>().forMetric(CoreMetrics.UNCOVERED_LINES).withValue(defaultCoverage.linesToCover() - defaultCoverage.coveredLines()));
      saveMeasure(defaultCoverage.inputFile(),
        new DefaultMeasure<String>().forMetric(CoreMetrics.COVERAGE_LINE_HITS_DATA).withValue(KeyValueFormat.format(defaultCoverage.hitsByLine())));
    }
    if (defaultCoverage.conditions() > 0) {
      saveMeasure(defaultCoverage.inputFile(), new DefaultMeasure<Integer>().forMetric(CoreMetrics.CONDITIONS_TO_COVER).withValue(defaultCoverage.conditions()));
      saveMeasure(defaultCoverage.inputFile(),
        new DefaultMeasure<Integer>().forMetric(CoreMetrics.UNCOVERED_CONDITIONS).withValue(defaultCoverage.conditions() - defaultCoverage.coveredConditions()));
      saveMeasure(defaultCoverage.inputFile(),
        new DefaultMeasure<String>().forMetric(CoreMetrics.COVERED_CONDITIONS_BY_LINE).withValue(KeyValueFormat.format(defaultCoverage.coveredConditionsByLine())));
      saveMeasure(defaultCoverage.inputFile(),
        new DefaultMeasure<String>().forMetric(CoreMetrics.CONDITIONS_BY_LINE).withValue(KeyValueFormat.format(defaultCoverage.conditionsByLine())));
    }
  }

  private static class BuildSyntaxHighlighting implements Function<SyntaxHighlightingRule, ScannerReport.SyntaxHighlightingRule> {
    private ScannerReport.SyntaxHighlightingRule.Builder builder = ScannerReport.SyntaxHighlightingRule.newBuilder();
    private ScannerReport.TextRange.Builder rangeBuilder = ScannerReport.TextRange.newBuilder();

    @Override
    public ScannerReport.SyntaxHighlightingRule apply(@Nonnull SyntaxHighlightingRule input) {
      builder.setRange(rangeBuilder.setStartLine(input.range().start().line())
        .setStartOffset(input.range().start().lineOffset())
        .setEndLine(input.range().end().line())
        .setEndOffset(input.range().end().lineOffset())
        .build());
      builder.setType(ScannerReportUtils.toProtocolType(input.getTextType()));
      return builder.build();
    }
  }

  @Override
  public void store(DefaultCpdTokens defaultCpdTokens) {
    InputFile inputFile = defaultCpdTokens.inputFile();
    PmdBlockChunker blockChunker = new PmdBlockChunker(getBlockSize(inputFile.language()));
    List<Block> blocks = blockChunker.chunk(inputFile.key(), defaultCpdTokens.getTokenLines());
    index.insert(inputFile, blocks);
  }

  @VisibleForTesting
  int getBlockSize(String languageKey) {
    int blockSize = settings.getInt("sonar.cpd." + languageKey + ".minimumLines");
    if (blockSize == 0) {
      blockSize = DefaultCpdBlockIndexer.getDefaultBlockSize(languageKey);
    }
    return blockSize;
  }

  @Override
  public void store(AnalysisError analysisError) {
    // no op
  }

  @Override
  public void storeProperty(String key, String value) {
    contextPropertiesCache.put(key, value);
  }
}
