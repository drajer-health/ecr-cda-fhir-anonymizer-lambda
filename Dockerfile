FROM public.ecr.aws/lambda/java:17

# Copy the compiled jar into the container
COPY target/ecr-cda-fhir-anonymizer-lambda-1.0.15.jar  ${LAMBDA_TASK_ROOT}/lib/
COPY target/classes/packages  ${LAMBDA_TASK_ROOT}/packages
COPY target/classes/hl7-xml-transforms  ${LAMBDA_TASK_ROOT}/hl7-xml-transforms

CMD ["com.drajer.ecr.anonymizer.AnonymizerLambdaFunctionHandler::handleRequest"]