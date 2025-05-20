import json
import boto3
from datetime import datetime
import uuid

# 初始化 DynamoDB 客户端
dynamodb = boto3.resource('dynamodb')
table_name = 'ASM'
table = dynamodb.Table(table_name)

# 初始化 AWS IoT 客户端
iot_client = boto3.client('iot-data', region_name='us-east-1')  # 确保区域正确

def lambda_handler(event, context):
    # 从事件中提取数据
    screen_brightness = event.get('screenBrightness', None)

    if screen_brightness is not None:
        record_id = str(uuid.uuid4())
        partition_key = f"DEVICE#{record_id}"
        timestamp = datetime.now().isoformat()

        # 将 Brightness 数据存储到 DynamoDB
        table.put_item(
            Item={
                'PK': partition_key,
                'SK': f"BRIGHTNESS#{timestamp}",
                'screenBrightness': screen_brightness,
                'timestamp': timestamp
            }
        )

        # 发布消息到 AWS/brightness/control 主题
        iot_client.publish(
            topic='AWS/brightness/control',
            qos=1,
            payload=json.dumps({'screenBrightness': screen_brightness})
        )

    return {
        'statusCode': 200,
        'body': json.dumps('Brightness data processed and published to control topic!')
    } 