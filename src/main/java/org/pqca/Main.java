/*
 * CBOMkit-action
 * Copyright (C) 2025 PQCA
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pqca;

import jakarta.annotation.Nonnull;
import java.io.File;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.pqca.indexing.IndexingService;
import org.pqca.indexing.go.GoIndexService;
import org.pqca.indexing.java.JavaIndexService;
import org.pqca.indexing.python.PythonIndexService;
import org.pqca.scanning.CBOM;
import org.pqca.scanning.Language;
import org.pqca.scanning.ScanResultDTO;
import org.pqca.scanning.ScannerService;
import org.pqca.scanning.go.GoScannerService;
import org.pqca.scanning.java.JavaScannerService;
import org.pqca.scanning.python.PythonScannerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("java:S106")
public class Main {
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    public static void main(@Nonnull String[] args) throws Exception {
        final String workspace = System.getenv("GITHUB_WORKSPACE");
        if (workspace == null) {
            LOGGER.error("Missing env var GITHUB_WORKSPACE");
            return;
        }

        final File projectDirectory = new File(workspace);

        // Create output dir
        final File outputDir =
                Optional.ofNullable(System.getenv("CBOMKIT_OUTPUT_DIR"))
                        .map(File::new)
                        .orElse(new File("cbom"));
        LOGGER.info("Writing CBOMs to {}", outputDir);
        outputDir.mkdirs();

        // Prepare scan
        final BomGenerator bomGenerator = new BomGenerator(projectDirectory, outputDir);

        final String excludePatternsStr = System.getenv("CBOMKIT_EXCLUDE");
        final List<String> excludePatterns =
                excludePatternsStr == null || excludePatternsStr.isEmpty()
                        ? null
                        : Arrays.stream(excludePatternsStr.split(","))
                                .map(s -> s.trim().toLowerCase())
                                .collect(Collectors.toList());

        final String languagesStr = System.getenv("CBOMKIT_LANGUAGES");
        List<Language> configuredLanguages =
                languagesStr == null || languagesStr.trim().isEmpty()
                        ? Arrays.asList(Language.values())
                        : Arrays.stream(languagesStr.split(","))
                                .map(s -> s.trim())
                                .map(String::toUpperCase)
                                .map(Language::valueOf)
                                .collect(Collectors.toList());

        // Statistics and aggegator
        long scanningTime = 0;
        int numberOfScannedFiles = 0;
        int numberOfScannedLines = 0;
        CBOM consolidatedCBOM = null;

        // Set up indexers and scanners
        final Map<Language, IndexingService> indexers = new HashMap<>();
        final Map<Language, ScannerService> scanners = new HashMap<>();

        // java
        if (configuredLanguages.contains(Language.JAVA)) {
            final JavaIndexService javaIndexService = new JavaIndexService(projectDirectory);
            indexers.put(Language.JAVA, javaIndexService);

            final JavaScannerService javaScannerService = new JavaScannerService(projectDirectory);
            javaScannerService.setRequireBuild(
                    Optional.ofNullable(System.getenv("CBOMKIT_JAVA_REQUIRE_BUILD"))
                            .map(String::trim)
                            .map(v -> v.equalsIgnoreCase("true"))
                            .orElse(true));
            javaScannerService.addJavaDependencyJar(projectDirectory.getAbsolutePath());
            javaScannerService.addJavaDependencyJar(
                    System.getProperty("user.home") + "/.m2/repository");
            javaScannerService.addJavaDependencyJar(System.getProperty("user.home") + "/.gradle");
            javaScannerService.addJavaDependencyJar(System.getenv("CBOMKIT_JAVA_JAR_DIR"));
            javaScannerService.addJavaClassDir(projectDirectory.getAbsolutePath());
            scanners.put(Language.JAVA, javaScannerService);
        }

        // python
        if (configuredLanguages.contains(Language.PYTHON)) {
            indexers.put(Language.PYTHON, new PythonIndexService(projectDirectory));
            scanners.put(Language.PYTHON, new PythonScannerService(projectDirectory));
        }

        // go
        if (configuredLanguages.contains(Language.GO)) {
            indexers.put(Language.GO, new GoIndexService(projectDirectory));
            scanners.put(Language.GO, new GoScannerService(projectDirectory));
        }

        // Generate CBOM
        for (Language language : configuredLanguages) {
            IndexingService indexer = indexers.get(language);
            indexer.setExcludePatterns(excludePatterns);
            ScannerService scanner = scanners.get(language);

            ScanResultDTO scanResultDTO = bomGenerator.generateBom(indexer, scanner);
            if (consolidatedCBOM != null) {
                consolidatedCBOM.merge(scanResultDTO.cbom());
            } else {
                consolidatedCBOM = scanResultDTO.cbom();
            }
            numberOfScannedFiles += scanResultDTO.numberOfScannedFiles();
            numberOfScannedLines += scanResultDTO.numberOfScannedLines();
            scanningTime += scanResultDTO.endTime() - scanResultDTO.startTime();
        }

        LOGGER.info(
                "Scanned {} files with {} lines in {} seconds.",
                numberOfScannedFiles,
                numberOfScannedLines,
                scanningTime / 1000.0);
        bomGenerator.writeCBOM(consolidatedCBOM, null);

        // Write output pattern
        final String githubOutput = System.getenv("GITHUB_OUTPUT");
        if (githubOutput != null) {
            try (final FileWriter outPutVarFileWriter = new FileWriter(githubOutput, true)) {
                outPutVarFileWriter.write("pattern=" + outputDir + "/cbom*.json\n");
            }
        }
    }
}
