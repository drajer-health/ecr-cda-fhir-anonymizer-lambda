# ecr-cda-fhir-anonymizer-lambda

S3 Trigger to convert CDA to FHIR format & Anonymize data

### Prerequisites:

1.  Java 8 or Higher
2.  AWS SDK - STS or Eclipse
3.  AWS Account
4.  Maven 3.3.x
5.  GIT

## Clone the Repository

Clone the repository using the below command in command prompt

`git clone https://github.com/drajer-health/ecr-cda-fhir-anonymizer-lambda.git`

## Create Build:

Import Project as Maven Project Build:

Navigate to `ecr-cda-fhir-anonymizer-lambda` directory  `..../` and run Maven build to build lambda jar file.

```
$ mvn clean

$ mvn clean install
```

This will generate a war file under target/ecr-cda-fhir-anonymizer-lambda-1.0.0.jar.

## AWS Lambda

### Deploy eCR FHIR to CDA Lambda:

Login to your AWS Account

1.  Click on Services then select Lambda

2.  Click on Create Function

3.  Select "Author from Scratch" option

4.  Enter:


```
Function Name: ecrCDA-FHIR-Anonymizer-lambda
Runtime: Java 8 on Amazon Linux 1 or any other Java runtimes
Permissions: Create a new role with basic Lambda permissions or select your organization specific security
```
5. Click on "Create Function"


## At this point Lambda function would be created, navigate to the newly created function and configure the lambda function and environment variable.

1. Go to the newly created Role.

2. Under `Permissions` tab click on `Add inline Policy`

3. Click on `{ } JSON` tab and ad the following security policy. Replace the `S3-BUCKET-NAME` with your S3 bucket name.
	```
	{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "ListObjectsInBucket",
            "Action": [
                "s3:GetObjectVersion",
                "s3:GetBucketLocation",
                "s3:GetObject",
                "s3:PutObject",
                "s3:PutObjectAcl"
            ],
            "Effect": "Allow",
            "Resource": [
                "arn:aws:s3:::S3-BUCKET-NAME/*"
            ]
        }
    ]
}
	``

4. Click on button `Review policy` and then click `Save changes`

5. Come back to your AWS Lambda Function and navigate to `Configuration` tab.

6. Go to the `General Configuration` and click on `Edit` button. Increase the Timeout to minimum 1 minute.

7.  Under the "Code" tab select "Upload from"

8. Select .zip or .jar file option.

9. Click upload and navigate to your local workspace target folder and select ecr-cda-fhir-anonymizer-lambda-1.0.0.jar and click "Save".

10. Click on "Edit" on "Runtime Settings".

11. Enter below value for Handler


```
com.drajer.ecr.anonymizer.AnonymizerLambdaFunctionHandler::handleRequest

```
12.  Click "Save"

### Lambda Configuration
To process the file from the S3 bucket, lambda function needs to be configured to process from the specified folder. Add the ***Environment Variable*** to the lambda function specifying the S3 bucket folder name.

1.  Click on "Configuration" tab and then "Environment Variables"

2.  Click on "Edit" to add new environment variable

3.  Click on "Add new environment variable"

4.  Enter


|Environment Variable| Value |
|--|--|
|BUCKET_NAME  | <- S3-FolderName ->  |


### Lambda Trigger
Lambda function needs to be triggered, for this we need to add and configure the trigger. Follow the following steps to add the trigger to your lambda function.
1. Go to you Lambda function

2. Click on `Add trigger`

3. From the `Trigger configuration` drop down select
	`S3` option

4. From the `Bucket` drop down select your bucket that this lambda function will listen.

5. Add the `Suffix` to add filter to the function. For example `RR`

6. Select the acknowledgement and click Add.


### At this point the Lambda function is created and configured to listen to the S3 Bucket.
