/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2013 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.batch.scan.filesystem;

import com.google.common.collect.Maps;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.HiddenFileFilter;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.BatchComponent;
import org.sonar.api.scan.filesystem.InputFile;
import org.sonar.api.scan.filesystem.InputFileFilter;
import org.sonar.api.scan.filesystem.ModuleFileSystem;
import org.sonar.api.scan.filesystem.PathResolver;

import javax.annotation.Nullable;
import java.io.File;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Index input files into {@link InputFileCache}.
 */
public class FileIndexer implements BatchComponent {

  private static final IOFileFilter DIR_FILTER = FileFilterUtils.and(HiddenFileFilter.VISIBLE, FileFilterUtils.notFileFilter(FileFilterUtils.prefixFileFilter(".")));
  private static final IOFileFilter FILE_FILTER = HiddenFileFilter.VISIBLE;

  private final PathResolver pathResolver = new PathResolver();
  private final List<InputFileFilter> filters;
  private final LanguageRecognizer languageRecognizer;
  private final InputFileCache cache;
  private final FileHashes fileHashes;

  // TODO support deprecated filters
  public FileIndexer(List<InputFileFilter> filters, LanguageRecognizer languageRecognizer,
                     InputFileCache cache, FileHashes fileHashes) {
    this.filters = filters;
    this.languageRecognizer = languageRecognizer;
    this.cache = cache;
    this.fileHashes = fileHashes;
  }

  public void index(ModuleFileSystem fileSystem) {
    Logger logger = LoggerFactory.getLogger(FileIndexer.class);
    logger.info("Index files");

    cache.removeModule(fileSystem.moduleKey());
    int count = 0;
    for (File sourceDir : fileSystem.sourceDirs()) {
      count += indexDirectory(fileSystem, sourceDir, InputFile.TYPE_SOURCE);
    }
    for (File testDir : fileSystem.testDirs()) {
      count += indexDirectory(fileSystem, testDir, InputFile.TYPE_TEST);
    }

    // TODO index additional sources and test files

    logger.info(String.format("%d files indexed", count));
  }

  private int indexDirectory(ModuleFileSystem fileSystem, File sourceDir, String type) {
    int count = 0;
    Collection<File> files = FileUtils.listFiles(sourceDir, FILE_FILTER, DIR_FILTER);
    for (File file : files) {
      InputFile input = newInputFile(fileSystem, sourceDir, type, file);
      if (accept(input)) {
        cache.put(fileSystem.moduleKey(), input);
        count++;
      }
    }
    return count;
  }

  private InputFile newInputFile(ModuleFileSystem fileSystem, File sourceDir, String type, File file) {
    try {
      Map<String, String> attributes = Maps.newHashMap();

      // paths
      String baseRelativePath = pathResolver.relativePath(fileSystem.baseDir(), file);
      set(attributes, InputFile.ATTRIBUTE_SOURCEDIR_PATH, FilenameUtils.normalize(sourceDir.getCanonicalPath(), true));
      set(attributes, InputFile.ATTRIBUTE_SOURCE_RELATIVE_PATH, pathResolver.relativePath(sourceDir, file));
      set(attributes, InputFile.ATTRIBUTE_CANONICAL_PATH, FilenameUtils.normalize(file.getCanonicalPath(), true));

      // other metadata
      set(attributes, InputFile.ATTRIBUTE_TYPE, type);
      String extension = FilenameUtils.getExtension(file.getName());
      set(attributes, InputFile.ATTRIBUTE_EXTENSION, extension);
      set(attributes, InputFile.ATTRIBUTE_LANGUAGE, languageRecognizer.ofExtension(extension));
      initStatus(file, fileSystem.sourceCharset(), baseRelativePath, attributes);

      return InputFile.create(file, baseRelativePath, attributes);

    } catch (Exception e) {
      throw new IllegalStateException("Fail to read file: " + file.getAbsolutePath(), e);
    }
  }

  private void initStatus(File file, Charset charset, String baseRelativePath, Map<String, String> attributes) {
    String hash = fileHashes.hash(file, charset);
    set(attributes, InputFile.ATTRIBUTE_HASH, hash);

    String remoteHash = fileHashes.remoteHash(baseRelativePath);
    // currently no need to store this remote hash in attributes
    if (StringUtils.equals(hash, remoteHash)) {
      set(attributes, InputFile.ATTRIBUTE_STATUS, InputFile.STATUS_SAME);
    } else if (StringUtils.isEmpty(remoteHash)) {
      set(attributes, InputFile.ATTRIBUTE_STATUS, InputFile.STATUS_ADDED);
    } else {
      set(attributes, InputFile.ATTRIBUTE_STATUS, InputFile.STATUS_CHANGED);
    }
  }

  private void set(Map<String, String> attributes, String key, @Nullable String value) {
    if (value != null) {
      attributes.put(key, value);
    }
  }

  private boolean accept(InputFile inputFile) {
    for (InputFileFilter filter : filters) {
      if (!filter.accept(inputFile)) {
        return false;
      }
    }
    return true;
  }
}
