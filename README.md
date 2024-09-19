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


### SQS Queue
Choose the SQS queue and click `Create Queue` 

1. Select `Standard` and Enter the Name for the Queue as `ecr-cda-fhir-anonymizer-s3-queue`
   
3. Enter 10 minutes as Visibility timeout
   
4. Server-Side encryption as `disabled`
   
5. Access Policy `Advanced`
   
6. Make neccessary changes to below and copy as in-line policy
   ```
   {
  "Version": "2012-10-17",
  "Id": "__default_policy_ID",
  "Statement": [
    {
      "Sid": "__owner_statement",
      "Effect": "Allow",
      "Principal": {
        "Service": "s3.amazonaws.com"
      },
      "Action": "SQS:SendMessage",
      "Resource": "arn:aws:sqs:us-east-1:<<AWS_ACCOUNT_INFO>>:<<QUEUE_NAME>>",
      "Condition": {
        "StringEquals": {
          "aws:SourceAccount": "<<AWS ACCOUNT INFO>> "
        },
        "ArnLike": {
          "aws:SourceArn": "arn:aws:s3:::<<S3 BUCKET NAME>>"
        }
      }
    }
  ]
}
```

6. Click Save

### Lambda Trigger
Lambda function needs to be triggered, for this we need to add and configure the trigger. Follow the following steps to add the trigger to your lambda function.
1. Go to you Lambda function

2. Click on `Add trigger`

3. From the `Trigger configuration` drop down select
	`SQS` option

4. Choose or enter ther ARN of an SQS queue `ecr-cda-fhir-anonymizer-s3-queue` created from above step.

5. Make changes as required or have the defualts 

6. Click Add.

### S3 Event Notification

1. Go to S3 bucket and to Properties Tab

2. Scroll down to `Event Notification` and Click `Create event Notification`

3. Enter Name `ecr-cda-fhir-anonymizer-s3-sqs-event`

4. Enter Prefix as `RR/`

5. Event Types as `All object create events`

6. Destination as `SQS queue`

7. Specify SQS queue as `ecr-cda-fhir-anonymizer-s3-queue`

8. Click `Save Changes` 


### At this point the Lambda function is created and configured to get messages from SQS whenever RR files are created in S3 Bucket.

### S3 Saxon License

Here are the steps to store the Saxon license in an S3 bucket and configure your AWS Lambda
function to use the bucket name as an environment variable:

1. Create an S3 Bucket (if you don't already have one):
	Go to the AWS Management Console.
	Navigate to S3.
	Click on "Create bucket."
	Choose a unique name for your bucket and select your preferred region.
	Click "Create bucket."

2. Upload the Saxon License File:
	In the S3 console, click on your bucket name to open it and create & open license folder if it doesn’t exis
	Click the "Upload" button.
	Select the Saxon license file (e.g., saxon-license.lic) from your local file system.
	Optionally, set the permissions for the file based on your access requirements.
	Click "Upload."

3. Set Up Environment Variable in Lambda
	Go to the AWS Management Console.
	Navigate to the Lambda service.
	Find and select the Lambda function that you want to configure.
	Scroll down to the "Environment variables" section.
	Click on "Edit."
	Add a new key-value pair:
		Key: BUCKET_NAME
		Value: <your-bucket-name> (replace this with the name of your S3
			bucket)

4. Test Your Lambda Function
	In the AWS Lambda console, you can create a test event based on the input your
	Lambda function expects.
	Click on "Test" to execute the function and check the logs to verify that it
	retrieves the license file from S3.

5. Check CloudWatch Logs
	If there are any issues, check the CloudWatch logs for your Lambda function to
	debug the problem.

By following these steps, you'll be able to store the Saxon license in an S3 bucket and configure
your Lambda function to use the bucket name as an environment variable. If you have any
further questions or need assistance with anything else, feel free to ask!
