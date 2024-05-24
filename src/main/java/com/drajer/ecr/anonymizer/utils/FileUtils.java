package com.drajer.ecr.anonymizer.utils;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public class FileUtils {

	private static final Logger logger = LoggerFactory.getLogger(FileUtils.class);

	private static final ObjectMapper mapper = new ObjectMapper();

	public static <T> T readFileContents(String filename, TypeReference<T> typeReference) {

		T object = null;
		try {
			InputStream inputStream = new ClassPathResource(filename).getInputStream();
			object = mapper.readValue(inputStream, typeReference);
		} catch (Exception e) {

			logger.info("Error in parsing : " + filename + " to Object");
		}

		return object;
	}

	/**
	 * The method saves the provided data to a file.
	 *
	 * @param data     -- The data to be saved.
	 * @param filename -- The filename to be used for saving the data.
	 */
	public static void saveDataToFile(String data, String filename) {
		try (Writer writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filename), "UTF-8"))) {
			logger.info("Writing data to file: {}", filename);
			writer.write(data);
		} catch (IOException e) {
			logger.debug("Unable to write data to file: {}", filename, e);
		}
	}
}
