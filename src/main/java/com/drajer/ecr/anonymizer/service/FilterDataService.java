package com.drajer.ecr.anonymizer.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

import com.amazonaws.services.s3.model.S3DataSource.Utils;
import com.drajer.ecr.anonymizer.utils.FileUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class FilterDataService {

	private final ObjectMapper objectMapper = new ObjectMapper();

	/**
	 * Processes the input JSON string by masking specified elements based on the
	 * provided configuration.
	 *
	 * @param json The JSON string to process.
	 * @return A {@link JsonNode} representing the processed JSON with masked
	 *         elements.
	 * @throws IOException If there is an error reading the JSON string.
	 */
	public JsonNode processJson(String json, String resourceType) throws IOException {
		JsonNode root = objectMapper.readTree(json);

		List<Map<String, Object>> maskedElements = getMaskedElementsForResource(resourceType);

		if (!maskedElements.isEmpty()) {
			for (Map<String, Object> item : maskedElements) {
				String targetElement = (String) item.get("targetElement");
				@SuppressWarnings("unchecked")
				Map<String, Object> maskedElement = (Map<String, Object>) item.get("maskedData");
				boolean removalAll = item.containsKey("removeAll") && (boolean) item.get("removeAll");

				if (targetElement != null) {
					List<String> paths = Arrays.asList(targetElement.split("\\."));

					process(root, paths, maskedElement, removalAll);
				}
			}
		}

		return root;

	}

	/**
	 * Processes a JSON node recursively based on the provided path.
	 *
	 * @param node           The JSON node to process.
	 * @param path           The path to match against.
	 * @param maskedElements A map containing elements to be masked.
	 * @param removeAll      Flag indicating whether to remove all matched elements.
	 */
	public void process(JsonNode node, List<String> path, Map<String, Object> maskedElements, boolean removeAll) {
		if (node.isObject()) {
			processObject((ObjectNode) node, path, maskedElements, removeAll);
		} else if (node.isArray()) {
			processArray((ArrayNode) node, path, maskedElements, removeAll);
		}
	}

	private void processObject(ObjectNode objectNode, List<String> path, Map<String, Object> maskedElements,
			boolean removeAll) {
		ObjectNode updatedObjectNode = JsonNodeFactory.instance.objectNode();
		List<String> fieldsToRemove = new ArrayList<>();

		Iterator<Entry<String, JsonNode>> fields = objectNode.fields();
		while (fields.hasNext()) {
			Entry<String, JsonNode> entry = fields.next();
			String key = entry.getKey();
			JsonNode value = entry.getValue();

			if (isMatchingPath(key, path)) {
				List<String> newPath = new ArrayList<>(path);
				newPath.remove(0);

				if (newPath.isEmpty() || removeAll) {
					fieldsToRemove.add(key);
					ObjectNode maskedNode = createMaskedNode(maskedElements, key);
					updatedObjectNode.setAll(maskedNode);
				} else {
					process(value, newPath, maskedElements, removeAll);
				}
			} else {
				process(value, path, maskedElements, removeAll);
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
	 * @param arrayNode     The {@link ArrayNode} containing JSON elements to
	 *                      process.
	 * @param pathToRemove  A {@link List} of {@link String}s representing the paths
	 *                      to be removed from JSON objects.
	 * @param maskedElement A {@link Map} containing the elements to be masked, with
	 *                      keys representing the paths and values representing the
	 *                      masking values.
	 * @param removeAll     A boolean flag indicating whether all occurrences of the
	 *                      specified path should be removed.
	 */
	private void processArray(ArrayNode arrayNode, List<String> pathToRemove, Map<String, Object> maskedElement,
			boolean removeAll) {
		ObjectNode updatedObjectNode = JsonNodeFactory.instance.objectNode();
		Iterator<JsonNode> elements = arrayNode.elements();
		while (elements.hasNext()) {
			JsonNode element = elements.next();
			if (element.isObject()) {
				ObjectNode objNode = (ObjectNode) element;
				Iterator<Entry<String, JsonNode>> fields = objNode.fields();
				List<String> fieldsToRemove = new ArrayList<>();
				while (fields.hasNext()) {
					Entry<String, JsonNode> entry = fields.next();
					String key = entry.getKey();
					JsonNode value = entry.getValue();
					if (isMatchingPath(key, pathToRemove)) {
						if (pathToRemove.size() == 1) {
							fieldsToRemove.add(key);
							ObjectNode maskedNode = createMaskedNode(maskedElement, key);
							updatedObjectNode.setAll(maskedNode);
						} else {
							if (!removeAll) {
								pathToRemove.remove(0);
							}
							if (value.isObject() || value.isArray()) {
								process(value, pathToRemove, maskedElement, removeAll);
							}
						}
					} else {
						if (value.isObject() || value.isArray()) {
							process(value, pathToRemove, maskedElement, removeAll);
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

	private ObjectNode createMaskedNode(Map<String, Object> maskedElement, String key) {
		ObjectNode maskedNode = JsonNodeFactory.instance.objectNode();

		if (maskedElement != null && !maskedElement.isEmpty()) {
			maskedElement.entrySet();
			for (Entry<String, Object> data : maskedElement.entrySet()) {
				if (key.equals(data.getKey()) || data.getKey().equals("_" + key)) {
					// Convert the value to a JsonNode
					JsonNode jsonNodeValue = objectMapper.valueToTree(data.getValue());
					maskedNode.set(data.getKey(), jsonNodeValue);
				}

			}
			return maskedNode;
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

	/**
	 * Retrieves the configuration list for the given key.
	 *
	 * @param key The key to look up in the configuration.
	 * @return A list of maps representing the configuration.
	 * @throws IllegalArgumentException If the value is not a list of maps.
	 */
	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> getConfigList(String key) {
		String ecrAnonymizerConfigFile = readPropertiesFile().getProperty("ecr.anonymizer.config.file");
		Map<String, List<Map<String, Object>>> ecrDataMaskingConfigList = FileUtils.readFileContents(ecrAnonymizerConfigFile,
				new TypeReference<Map<String, List<Map<String, Object>>>>() {
				});
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

}
