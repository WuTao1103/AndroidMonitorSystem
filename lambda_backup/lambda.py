import json
import boto3
import uuid
from datetime import datetime

# 初始化 DynamoDB 客户端
dynamodb = boto3.resource('dynamodb')
table_name = 'ASM'  # 确保表名正确
table = dynamodb.Table(table_name)

def lambda_handler(event, context):
    # 从事件中提取数据
    wifi_status = event.get('wifiStatus', 'UNKNOWN')
    connected_ssid = event.get('connectedSSID', 'UNKNOWN')
    bluetooth_status = event.get('bluetoothStatus', 'UNKNOWN')
    paired_devices_count = event.get('pairedDevicesCount', 0)
    screen_brightness = event.get('screenBrightness', -1)

    # 生成唯一 ID
    record_id = str(uuid.uuid4())

    # 使用单表设计，设置分区键和排序键
    partition_key = f"DEVICE#{record_id}"
    sort_key = f"STATUS#{datetime.now().isoformat()}"

    # 将数据存储到 DynamoDB
    response = table.put_item(
        Item={
            'PK': partition_key,
            'SK': sort_key,
            'wifiStatus': wifi_status,
            'connectedSSID': connected_ssid,
            'bluetoothStatus': bluetooth_status,
            'pairedDevicesCount': paired_devices_count,
            'screenBrightness': screen_brightness,
            'timestamp': datetime.now().isoformat()
        }
    )

    return {
        'statusCode': 200,
        'body': json.dumps('Data stored in DynamoDB!')
    }