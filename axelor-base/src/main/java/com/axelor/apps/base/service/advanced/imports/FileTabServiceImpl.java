/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2022 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.base.service.advanced.imports;

import com.axelor.apps.base.db.FileField;
import com.axelor.apps.base.db.FileTab;
import com.axelor.apps.base.db.repo.FileFieldRepository;
import com.axelor.db.mapper.Mapper;
import com.axelor.inject.Beans;
import com.axelor.meta.db.MetaField;
import com.axelor.meta.db.MetaJsonField;
import com.axelor.meta.db.MetaJsonModel;
import com.axelor.meta.db.MetaModel;
import com.axelor.meta.db.repo.MetaFieldRepository;
import com.axelor.meta.db.repo.MetaJsonFieldRepository;
import com.axelor.meta.db.repo.MetaModelRepository;
import com.google.common.base.Strings;
import com.google.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import wslite.json.JSONException;
import wslite.json.JSONObject;

public class FileTabServiceImpl implements FileTabService {

  @Inject MetaFieldRepository metaFieldRepo;

  @Inject MetaJsonFieldRepository metaJsonFieldRepo;

  @Inject FileFieldService fileFieldService;

  @Override
  public FileTab updateFields(FileTab fileTab) throws ClassNotFoundException {
    return fileTab.getIsJson() ? this.updateJsonFields(fileTab) : this.updateRealFields(fileTab);
  }

  private FileTab updateJsonFields(FileTab fileTab) throws ClassNotFoundException {
    MetaJsonModel model = fileTab.getJsonModel();
    if (model == null || CollectionUtils.isEmpty(fileTab.getFileFieldList())) {
      return fileTab;
    }
    Beans.get(ValidatorService.class).sortFileFieldList(fileTab.getFileFieldList());
    for (FileField fileField : fileTab.getFileFieldList()) {

      MetaJsonField importField =
          metaJsonFieldRepo
              .all()
              .filter(
                  "self.title = ?1 AND self.jsonModel = ?2",
                  fileField.getColumnTitle(),
                  model.getId())
              .fetchOne();
      if (importField != null) {
        fileField.setJsonField(importField);
        String relationship = importField.getType();
        if (relationship.equals("json-one-to-many") || relationship.equals("one-to-many")) {
          continue;
        }
        if (importField.getTargetJsonModel() != null
            || !Strings.isNullOrEmpty(importField.getTargetModel())) {
          fileField.setImportType(FileFieldRepository.IMPORT_TYPE_FIND);
          String subImportField = this.getSubImportField(null, importField);
          fileField.setSubImportField(subImportField);
        }

        fileField = fileFieldService.fillType(fileField);
        fileField.setFullName(fileFieldService.computeFullName(fileField));
      } else {
        fileField.setImportField(null);
        fileField.setSubImportField(null);
      }
    }
    return fileTab;
  }

  private FileTab updateRealFields(FileTab fileTab) throws ClassNotFoundException {
    MetaModel model = fileTab.getMetaModel();

    if (model == null || CollectionUtils.isEmpty(fileTab.getFileFieldList())) {
      return fileTab;
    }

    Beans.get(ValidatorService.class).sortFileFieldList(fileTab.getFileFieldList());

    for (FileField fileField : fileTab.getFileFieldList()) {

      MetaField importField =
          metaFieldRepo
              .all()
              .filter(
                  "self.label = ?1 AND self.metaModel.id = ?2",
                  fileField.getColumnTitle(),
                  model.getId())
              .fetchOne();

      if (importField != null) {
        String relationship = importField.getRelationship();
        if (!Strings.isNullOrEmpty(relationship) && relationship.equals("OneToMany")) {
          continue;
        }

        fileField.setImportField(importField);
        if (!Strings.isNullOrEmpty(relationship)) {
          String subImportField = this.getSubImportField(importField, null);
          fileField.setSubImportField(subImportField);
        }
        fileField = fileFieldService.fillType(fileField);

        if (!Strings.isNullOrEmpty(relationship) && !fileField.getTargetType().equals("MetaFile")) {
          fileField.setImportType(FileFieldRepository.IMPORT_TYPE_FIND);
        } else {
          if (!Strings.isNullOrEmpty(fileField.getTargetType())
              && fileField.getTargetType().equals("MetaFile")) {
            fileField.setImportType(FileFieldRepository.IMPORT_TYPE_NEW);
          }
        }
        fileField.setFullName(fileFieldService.computeFullName(fileField));
      } else {
        fileField.setImportField(null);
        fileField.setSubImportField(null);
      }
    }
    return fileTab;
  }

  private String getSubImportField(MetaField metaField, MetaJsonField jsonField)
      throws ClassNotFoundException {
    String modelName = "";

    if (jsonField != null && jsonField.getTargetJsonModel() != null) {
      return jsonField.getTargetJsonModel().getNameField();
    } else if (jsonField != null && !Strings.isNullOrEmpty(jsonField.getTargetModel())) {
      modelName = StringUtils.substringAfterLast(jsonField.getTargetModel(), ".");
    } else if (metaField != null) {
      modelName = metaField.getTypeName();
    }

    MetaModel metaModel = Beans.get(MetaModelRepository.class).findByName(modelName);

    AdvancedImportService advancedImportService = Beans.get(AdvancedImportService.class);
    Mapper mapper = advancedImportService.getMapper(metaModel.getFullName());

    return (mapper != null && mapper.getNameField() != null)
        ? mapper.getNameField().getName()
        : null;
  }

  @Override
  public FileTab compute(FileTab fileTab) {
    if (CollectionUtils.isEmpty(fileTab.getFileFieldList())) {
      return fileTab;
    }

    for (FileField fileField : fileTab.getFileFieldList()) {
      fileField.setFullName(fileFieldService.computeFullName(fileField));
    }
    return fileTab;
  }

  @Override
  public Map<String, Object> getImportedRecordMap(String recordString) throws JSONException {
    if (Strings.isNullOrEmpty(recordString)) {
      return null;
    }
    Map<String, Object> dataMap = new HashMap<String, Object>();
    List<Object> recordList = new ArrayList<>();

    JSONObject jsonObject = new JSONObject(recordString);
    for (Object key : jsonObject.keySet()) {
      String model = (String) key;
      boolean isJson = model.startsWith("JSON");
      Map<String, Object> recordMap = new HashMap<>();
      recordMap.put("model", isJson ? model.substring(5) : key);
      recordMap.put("ids", jsonObject.get(key));
      recordMap.put("isJson", isJson);
      recordList.add(recordMap);
    }

    dataMap.put("$recordMap", recordList);
    return dataMap;
  }

  @Override
  public void setIsJson(FileTab fileTab) {
    if (fileTab == null) {
      return;
    }

    boolean isJson = fileTab.getIsJson();
    List<FileField> fileFieldsList = new ArrayList<>();

    for (FileField fileField : fileTab.getFileFieldList()) {
      if (fileField == null) {
        continue;
      }

      fileField.setIsJson(isJson);
      fileFieldsList.add(fileField);
    }

    fileTab.setFileFieldList(fileFieldsList);
  }
}
