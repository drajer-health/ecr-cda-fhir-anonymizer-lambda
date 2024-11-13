package com.drajer.ecr.anonymizer.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;

import com.amazonaws.services.s3.model.S3DataSource.Utils;
import com.drajer.ecr.anonymizer.utils.FileUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Resource;

public class FilterDataService {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private Map<String, List<Map<String, Object>>> ecrDataMaskingConfigList = null;

	public FilterDataService() {
		String ecrAnonymizerConfigFile = readPropertiesFile().getProperty("ecr.anonymizer.config.file");
		ecrDataMaskingConfigList = FileUtils.readFileContents(ecrAnonymizerConfigFile,
				new TypeReference<Map<String, List<Map<String, Object>>>>() {
				});
	}

	/**
	 * Processes the input JSON string by masking specified elements based on the
	 * provided configuration.
	 *
	 * @param json The JSON string to process.
	 * @return A {@link JsonNode} representing the processed JSON with masked
	 *         elements.
	 * @throws IOException If there is an error reading the JSON string.
	 */
	public JsonNode processJson(String json, Map<String, Object> metaDataMap, String resourceType) throws IOException {
		JsonNode root = objectMapper.readTree(json);

		List<Map<String, Object>> maskedElements = getMaskedElementsForResource(resourceType);

		if (!maskedElements.isEmpty()) {
			for (Map<String, Object> item : maskedElements) {
				String targetElement = (String) item.get("targetElement");

				boolean removeAll = Optional.ofNullable((Boolean) item.get("removeAll")).orElse(false);
				boolean referenceSpecific = Optional.ofNullable((Boolean) item.get("referenceSpecific")).orElse(false);

				if (targetElement != null) {
					List<String> paths = new ArrayList(Arrays.asList(targetElement.split("\\.")));
					item.put("metaData", metaDataMap);
					process(root, paths, removeAll, item, referenceSpecific);
				}
			}
		}

		return root;

	}

	/**
	 * Processes a JSON node recursively based on the provided path.
	 *
	 * @param node       The JSON node to process.
	 * @param path       The path to match against.
	 * @param removeAll  Flag indicating whether to remove all matched elements.
	 * @param maskedData
	 */
	public void process(JsonNode node, List<String> path, boolean removeAll, Map<String, Object> maskedData,
			boolean referenceSpecific) {
		if (node.isObject()) {
			processObject((ObjectNode) node, path, removeAll, maskedData, referenceSpecific);
		} else if (node.isArray()) {
			processArray((ArrayNode) node, path, removeAll, maskedData, referenceSpecific);
		}
	}

	private void processObject(ObjectNode objectNode, List<String> path, boolean removeAll,
			Map<String, Object> maskedData, boolean referenceSpecific) {
		ObjectNode updatedObjectNode = JsonNodeFactory.instance.objectNode();
		List<String> fieldsToRemove = new ArrayList<>();

		Iterator<Entry<String, JsonNode>> fields = objectNode.fields();
		while (fields.hasNext()) {
			Entry<String, JsonNode> entry = fields.next();
			String key = entry.getKey();
			JsonNode value = entry.getValue();

			if (referenceSpecific && value.isObject()) {
				ObjectNode maskedNode = referenceDisplayMasking(maskedData, value, key);
				ObjectNode maskedobj = JsonNodeFactory.instance.objectNode();
				if (maskedNode != null && !maskedNode.isEmpty()) {
					fieldsToRemove.add(key);
					maskedobj.set(key, (JsonNode) maskedNode);

					updatedObjectNode.setAll(maskedobj);
				}

			}

			if (isMatchingPath(key, path)) {
				List<String> newPath = new ArrayList<>(path);
				newPath.remove(0);

				if (newPath.isEmpty() || removeAll) {

					fieldsToRemove.add(key);
					ObjectNode maskedNode = createMaskedNode(maskedData, key, value);
					updatedObjectNode.setAll(maskedNode);
				} else {
					process(value, newPath, removeAll, maskedData, referenceSpecific);
				}
			} else {
				process(value, path, removeAll, maskedData, referenceSpecific);
			}
		}

		for (String fieldName : fieldsToRemove) {
			objectNode.remove(fieldName);
		}
		objectNode.setAll(updatedObjectNode);
	}

	/**
	 * Optimized method for processing an array of JSON elements, removing specified
	 * paths from all occurrences, and masking elements as required.
	 *
	 * @param arrayNode    The {@link ArrayNode} containing JSON elements to
	 *                     process.
	 * @param pathToRemove A {@link List} of {@link String}s representing the paths
	 *                     to be removed from JSON objects.
	 * @param maskedData   A {@link Map} containing the elements to be masked, with
	 *                     keys representing the paths and values representing the
	 *                     masking values.
	 * @param removeAll    A boolean flag indicating whether all occurrences of the
	 *                     specified path should be removed.
	 */
	private void processArray(ArrayNode arrayNode, List<String> pathToRemove, boolean removeAll,
			Map<String, Object> maskedData, boolean referenceSpecific) {
		ObjectNode updatedObjectNode = JsonNodeFactory.instance.objectNode();

		Iterator<JsonNode> elements = arrayNode.elements();
		while (elements.hasNext()) {
			List<String> referencePath = new ArrayList<>();
			JsonNode element = elements.next();
			if (element.isObject()) {
				ObjectNode objNode = (ObjectNode) element;

				if (referenceSpecific) {
					boolean isreferenceDisplayPresent = isreferenceDisplayPresent(element);
					if (isreferenceDisplayPresent) {
						referencePath.add("display");

					}

				}

				Iterator<Entry<String, JsonNode>> fields = objNode.fields();
				List<String> fieldsToRemove = new ArrayList<>();
				while (fields.hasNext()) {
					Entry<String, JsonNode> entry = fields.next();
					String key = entry.getKey();
					JsonNode value = entry.getValue();
					List<String> newPath = new ArrayList<>(pathToRemove);

					if (!referencePath.isEmpty()) {

						if (isMatchingPath(key, referencePath)) {
							fieldsToRemove.add(key);
							ObjectNode maskedNode = createMaskedNode(maskedData, key, value);
							updatedObjectNode.setAll(maskedNode);
						}

					}

					else if (isMatchingPath(key, pathToRemove)) {
						if (pathToRemove.size() == 1) {
							fieldsToRemove.add(key);
							ObjectNode maskedNode = createMaskedNode(maskedData, key, value);
							updatedObjectNode.setAll(maskedNode);
						} else {
							if (!removeAll) {
								newPath.remove(0);
							}
							if (value.isObject() || value.isArray()) {
								process(value, newPath, removeAll, maskedData, referenceSpecific);
							}
						}
					} else {
						if (value.isObject() || value.isArray()) {

							if (referenceSpecific && value.isObject()) {
								ObjectNode maskedNode = referenceDisplayMasking(maskedData, value, key);
								ObjectNode maskedobj = JsonNodeFactory.instance.objectNode();
								if (maskedNode != null && !maskedNode.isEmpty()) {
									fieldsToRemove.add(key);
									maskedobj.set(key, (JsonNode) maskedNode);

									updatedObjectNode.setAll(maskedobj);
								}

							} else {
								process(value, newPath, removeAll, maskedData, referenceSpecific);
							}
						}
					}
				}
				for (String fieldName : fieldsToRemove) {
					objNode.remove(fieldName);
				}
				objNode.setAll(updatedObjectNode);
			}
		}
	}

	private boolean isMatchingPath(String key, List<String> path) {
		if (path.size() == 0) {
			return false;
		}
		return key.equals(path.get(0));
	}

	public ObjectNode createMaskedNode(Map<String, Object> maskedData, String key, JsonNode value) {
		ObjectNode maskedNode = JsonNodeFactory.instance.objectNode();

		String action = Optional.ofNullable((String) maskedData.get("action")).orElse("");

		switch (action.toLowerCase()) {
		case "mask":
			return applyMask(maskedNode, maskedData, key);
		case "transform":
			return applyTransformation(maskedNode, maskedData, key, value);

		case "condition-mask":
			return applyConditionMaskByType(maskedNode, maskedData, key, value);

		default:
			return maskedNode;
		}
	}

	/**
	 * Applies masking to the JSON node based on the provided masked data.
	 *
	 * @param maskedNode the ObjectNode to which masked data will be added.
	 * @param maskData   a map containing the masking data.
	 * @param key        the key to be checked against the masked data.
	 * @return an ObjectNode with the masked data.
	 */
	private ObjectNode applyMask(ObjectNode maskedNode, Map<String, Object> maskData, String key) {
		Map<String, Object> maskedElement = (Map<String, Object>) maskData.get("maskedData");

		if (maskedElement != null && !maskedElement.isEmpty()) {
			maskedElement.forEach((dataKey, dataValue) -> {
				JsonNode jsonNodeValue = objectMapper.valueToTree(dataValue);
				maskedNode.set(dataKey, jsonNodeValue);

			});
		}
		return maskedNode;
	}

	/**
	 * Applies transformation to the JSON node based on the provided transformation
	 * data.
	 *
	 * @param maskedNode the ObjectNode to which transformed data will be added.
	 * @param maskData   a map containing the transformation data.
	 * @param key        the key to be checked against the transformation data.
	 * @param value      the original value associated with the key.
	 * @return an ObjectNode with the transformed data.
	 */
	private ObjectNode applyTransformation(ObjectNode maskedNode, Map<String, Object> maskData, String key,
			JsonNode value) {
		Map<String, Object> transformationRule = (Map<String, Object>) maskData.get("transformation");

		if (transformationRule != null && !transformationRule.isEmpty()) {
			return transformField(maskedNode, transformationRule, key, value);
		}
		return maskedNode;
	}

	private ObjectNode applyConditionMask(ObjectNode maskedNode, Map<String, Object> maskData, String key,
			JsonNode value) {
		Map<String, Object> conditionRule = (Map<String, Object>) maskData.get("condition");
		Map<String, Object> metaData = (Map<String, Object>) maskData.get("metaData");

		if (!isValidCondition(conditionRule, value)) {
			maskedNode.set(key, value);
			return maskedNode;
		}

		List<String> conditionValueList = getConditionValueList(conditionRule, metaData);
		String lowercaseValue = value.textValue().toLowerCase();
		if (conditionValueList.stream().map(String::toLowerCase).noneMatch(lowercaseValue::equals)) {
			return applyMask(maskedNode, maskData, key);
		}

		maskedNode.set(key, value);
		return maskedNode;
	}


	/**
	 * Transforms the field based on the provided transformation rules.
	 *
	 * @param maskedNode     the ObjectNode to which transformed data will be added.
	 * @param transformation a map containing the transformation rules.
	 * @param key            the key to be checked against the transformation rules.
	 * @param value          the original value associated with the key.
	 * @return an ObjectNode with the transformed data.
	 */
	private ObjectNode transformField(ObjectNode maskedNode, Map<String, Object> transformation, String key,
									  JsonNode value) {
		String type = Optional.ofNullable((String) transformation.get("type")).orElse("");

		if ("truncate".equalsIgnoreCase(type)) {
			int max = Optional.ofNullable((Integer) transformation.get("max")).orElse(0);

			if (value != null && value.isTextual() && value.textValue().length() > max) {

				String textValue = value.textValue();

				StringBuilder truncatedValue = new StringBuilder(textValue.substring(0, max));
				for (int i=max;i<textValue.length();i++)
				{
					truncatedValue.append("0");
				}
				maskedNode.set(key, TextNode.valueOf(truncatedValue.toString()));
			}
		}
		return maskedNode;
	}

	/**
	 * Retrieves the list of masked elements for the given resource type, combining
	 * specific and global masked elements.
	 *
	 * @param resourceType The resource type to look up.
	 * @return A list of maps representing the masked elements.
	 */
	private List<Map<String, Object>> getMaskedElementsForResource(String resourceType) {
		List<Map<String, Object>> resourceMaskedElements = getConfigList(resourceType);
		List<Map<String, Object>> globalMaskedElements = getConfigList("globalMaskedElements");

		List<Map<String, Object>> combinedMaskedElements = new ArrayList<>();
		if (resourceMaskedElements != null) {
			combinedMaskedElements.addAll(resourceMaskedElements);
		}
		if (globalMaskedElements != null) {
			combinedMaskedElements.addAll(globalMaskedElements);
		}

		return combinedMaskedElements;
	}

	private List<String> getConditionValueList(Map<String, Object> conditionRule, Map<String, Object> metaData) {
		List<String> conditionValueList = (List<String>) conditionRule.getOrDefault("values", Collections.emptyList());
		Object conditionValueObj = metaData != null ? metaData.get(conditionRule.get("key")) : null;

		if (conditionValueObj instanceof String) {

			conditionValueList.addAll(new ArrayList(Arrays.asList(((String) conditionValueObj).split(","))));

		} else if (conditionValueObj instanceof List) {
			conditionValueList.addAll((List<String>) conditionValueObj);
		}
		return conditionValueList;
	}

	/**
	 * Retrieves the configuration list for the given key.
	 *
	 * @param key The key to look up in the configuration.
	 * @return A list of maps representing the configuration.
	 * @throws IllegalArgumentException If the value is not a list of maps.
	 */
	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> getConfigList(String key) {

		if (ecrDataMaskingConfigList == null) {
			String ecrAnonymizerConfigFile = readPropertiesFile().getProperty("ecr.anonymizer.config.file");
			ecrDataMaskingConfigList = FileUtils.readFileContents(ecrAnonymizerConfigFile,
					new TypeReference<Map<String, List<Map<String, Object>>>>() {
					});

		}
		Object value = ecrDataMaskingConfigList.get(key);
		if (value != null) {
			if (value instanceof List) {
				List<?> list = (List<?>) value;
				if (list.isEmpty() || list.get(0) instanceof Map) {
					return (List<Map<String, Object>>) list;
				}
			}
			throw new IllegalArgumentException("The value for key '" + key + "' is not a list of maps.");
		}
		return null;
	}

	private boolean isValidCondition(Map<String, Object> conditionRule, JsonNode value) {
		return conditionRule != null && !conditionRule.isEmpty() && value != null && value.isTextual();
	}

	private Properties readPropertiesFile() {
		ClassLoader classLoader = Utils.class.getClassLoader();
		try {
			InputStream is = classLoader.getResourceAsStream("application.properties");
			Properties prop = new Properties();
			prop.load(is);
			return prop;
		} catch (Exception e) {
			return null;
		}
	}

	public List<Map<String, Object>> filterOrganizationsByJurisdictions(List<Map<String, Object>> resourceList,
			Map<String, Object> metaDataMap, String resourceType) {
		List<Map<String, Object>> maskedElements = getMaskedElementsForResource(resourceType);
		List<Map<String, Object>> filteredOrganizations = new ArrayList<>();

		if (!maskedElements.isEmpty()) {
			for (Map<String, Object> item : maskedElements) {
				String targetElement = (String) item.get("targetElement");
				if (targetElement != null) {

					item.put("metaData", metaDataMap);
					for (Map<String, Object> resourceMap : resourceList) {

						Resource resource = (Resource) resourceMap.get("resource");
						if (resource instanceof Organization) {
							Organization organization = (Organization) resource;
							List<Identifier> identifiers = organization.getIdentifier();
							boolean isValidOrganization = true;

							for (Identifier identifier : identifiers) {
								if (!checkValidStateByJurisdictionsToRetain(item, identifier.getValue())) {
									isValidOrganization = false;
									break;
								}
							}

							if (isValidOrganization) {
								filteredOrganizations.add(resourceMap);
							}
						}
					}
				}
			}
		}

		return filteredOrganizations;
	}

	private boolean checkValidStateByJurisdictionsToRetain(Map<String, Object> maskData, String value) {
		Map<String, Object> conditionRule = (Map<String, Object>) maskData.get("condition");
		Map<String, Object> metaData = (Map<String, Object>) maskData.get("metaData");

		if (conditionRule == null || conditionRule.isEmpty() || value == null) {
			return false;
		}

		List<String> conditionValueList = getConditionValueList(conditionRule, metaData);
		String lowercaseValue = value.toLowerCase();

		return conditionValueList.isEmpty() || conditionValueList.stream().anyMatch(lowercaseValue::equalsIgnoreCase);
	}

	public ObjectNode referenceDisplayMasking(Map<String, Object> maskData, JsonNode value, String key) {

		ObjectNode maskedNode = JsonNodeFactory.instance.objectNode();

		JsonNode referenceNode = value.get("reference");
		JsonNode displayNode = value.get("display");

		if (referenceNode != null && displayNode != null) {
			ObjectNode maskedObj = JsonNodeFactory.instance.objectNode();
			maskedNode.set("reference", referenceNode);
			maskedNode.setAll(applyMask(maskedObj, maskData, "display"));

			// maskedNode.set(key, (JsonNode) maskedReferenceObj);
			return maskedNode;

		}

		return maskedNode;
	}

	public boolean isreferenceDisplayPresent(JsonNode value) {

		JsonNode referenceNode = value.get("reference");
		JsonNode displayNode = value.get("display");

		if (referenceNode != null && displayNode != null) {
			return true;

		}

		return false;
	}

	public ObjectNode applyConditionMaskByType(ObjectNode maskedNode, Map<String, Object> maskData, String key,
			JsonNode value) {
		Map<String, Object> conditionRule = (Map<String, Object>) maskData.get("condition");

		if (conditionRule == null || conditionRule.isEmpty() || value == null) {
			maskedNode.set(key, value);
			return maskedNode;
		}

		String type = (String) conditionRule.getOrDefault("type", "");
		List<String> conditionValueList = (List<String>) conditionRule.getOrDefault("values", Collections.emptyList());

		switch (type.toLowerCase()) {
		case "key-match":
			return applyKeyMatchCondition(maskedNode, conditionValueList, maskData, key, value);
		case "value-match":
			return applyValueMatchCondition(maskedNode, conditionRule, maskData, key, value);
		default:
			maskedNode.set(key, value);
			return maskedNode;
		}
	}

	private ObjectNode applyKeyMatchCondition(ObjectNode maskedNode, List<String> conditionValueList,
			Map<String, Object> maskData, String key, JsonNode value) {
		if (value.isArray()) {
			ArrayNode arrayNode = (ArrayNode) value;
			for (JsonNode jsonNode : arrayNode) {
				if (jsonNode.isObject()) {
					ObjectNode objNode = (ObjectNode) jsonNode;
					Iterator<Entry<String, JsonNode>> fields = objNode.fields();
					while (fields.hasNext()) {
						Entry<String, JsonNode> entry = fields.next();
						String objKey = entry.getKey();
						if (objKey != null && conditionValueList.contains(objKey)) {
							return applyMask(maskedNode, maskData, key);
						}
					}
				}
			}
		} else if (value.isObject()) {
			ObjectNode objNode = (ObjectNode) value;
			Iterator<Entry<String, JsonNode>> fields = objNode.fields();
			while (fields.hasNext()) {
				Entry<String, JsonNode> entry = fields.next();
				String objKey = entry.getKey();
				if (objKey != null && conditionValueList.contains(objKey)) {
					return applyMask(maskedNode, maskData, key);
				}
			}
		}

		maskedNode.set(key, value);
		return maskedNode;
	}

	private ObjectNode applyValueMatchCondition(ObjectNode maskedNode, Map<String, Object> conditionRule,
			Map<String, Object> maskData, String key, JsonNode value) {
		List<String> conditionValueList = (List<String>) conditionRule.getOrDefault("values", Collections.emptyList());
		String conditionkey = (String) conditionRule.getOrDefault("key", "");

		if (value.isArray()) {
			ArrayNode arrayNode = (ArrayNode) value;
			for (JsonNode jsonNode : arrayNode) {
				if (jsonNode.isObject()) {
					ObjectNode objNode = (ObjectNode) jsonNode;
					Iterator<Entry<String, JsonNode>> fields = objNode.fields();
					while (fields.hasNext()) {
						Entry<String, JsonNode> entry = fields.next();
						String objKey = entry.getKey();
						JsonNode objValue = entry.getValue();
						if (objKey != null && conditionkey.equals(objKey) && objValue.isTextual()
								&& conditionValueList.contains(objValue)) {

							return applyMask(maskedNode, maskData, key);
						}
					}
				}
			}
		} else if (value.isObject()) {
			ObjectNode objNode = (ObjectNode) value;
			Iterator<Entry<String, JsonNode>> fields = objNode.fields();
			while (fields.hasNext()) {
				Entry<String, JsonNode> entry = fields.next();
				String objKey = entry.getKey();
				JsonNode objValue = entry.getValue();
				if (objKey != null && conditionkey.equals(objKey) && objValue.isTextual()
						&& conditionValueList.contains(objValue)) {
					return applyMask(maskedNode, maskData, key);
				}
			}
		}

		maskedNode.set(key, value);
		return maskedNode;
	}

}
