import json
import boto3
from datetime import datetime
import uuid
import os
import logging

# 设置日志
logger = logging.getLogger()
logger.setLevel(logging.INFO)

# 初始化 DynamoDB 客户端
dynamodb = boto3.resource('dynamodb')
table_name = os.environ.get('DYNAMODB_TABLE', 'AMS')  
table = dynamodb.Table(table_name)

# 初始化 AWS IoT 客户端
iot_client = boto3.client('iot-data', region_name='us-east-1')  # 确保区域正确

def lambda_handler(event, context):
    """
    处理从 IoT Core 接收的设备监控数据，并存储到 DynamoDB
    支持单独指标消息和组合设备状态消息
    """
    try:
        # 记录收到的事件
        logger.info(f"收到事件: {json.dumps(event)}")
        
        # 获取设备ID - 如果事件中没有，则使用默认ID
        device_id = event.get('deviceId', 'android-device')
        
        # 从事件获取时间戳，如果没有则生成新的
        timestamp_ms = event.get('timestamp')
        if timestamp_ms:
            # 将毫秒时间戳转换为ISO格式
            timestamp = datetime.fromtimestamp(timestamp_ms/1000).isoformat()
        else:
            timestamp = datetime.now().isoformat()
        
        logger.info(f"使用时间戳: {timestamp}")
        
        # 创建基础分区键
        partition_key = f"DEVICE#{device_id}"
        
        # 存储原始事件数据（确保每个消息都被记录）
        raw_event_item = {
            'PK': partition_key,
            'SK': f"RAW_EVENT#{timestamp}",
            'timestamp': timestamp,
            'raw_data': json.dumps(event)
        }
        table.put_item(Item=raw_event_item)
        logger.info("已保存原始事件数据")
        
        # 检查是否为设备状态组合消息（包含多个指标）
        is_combined_status = all(key in event for key in ['wifiStatus', 'bluetoothStatus', 'screenBrightness'])
        
        # 处理组合设备状态消息
        if is_combined_status:
            logger.info("检测到组合设备状态消息")
            
            # 1. 存储WiFi数据
            if 'wifiStatus' in event and 'connectedSSID' in event:
                wifi_item = {
                    'PK': partition_key,
                    'SK': f"WIFI#{timestamp}",
                    'wifiStatus': event['wifiStatus'],
                    'connectedSSID': event['connectedSSID'],
                    'timestamp': timestamp
                }
                table.put_item(Item=wifi_item)
                logger.info(f"WiFi数据已存储: {wifi_item}")
            
            # 2. 存储蓝牙数据
            if 'bluetoothStatus' in event:
                bluetooth_item = {
                    'PK': partition_key,
                    'SK': f"BLUETOOTH#{timestamp}",
                    'bluetoothStatus': event['bluetoothStatus'],
                    'pairedDevicesCount': event.get('pairedDevicesCount', 0),
                    'timestamp': timestamp
                }
                table.put_item(Item=bluetooth_item)
                logger.info(f"蓝牙数据已存储: {bluetooth_item}")
            
            # 3. 存储亮度数据
            if 'screenBrightness' in event:
                brightness_item = {
                    'PK': partition_key,
                    'SK': f"BRIGHTNESS#{timestamp}",
                    'screenBrightness': event['screenBrightness'],
                    'timestamp': timestamp
                }
                table.put_item(Item=brightness_item)
                logger.info(f"亮度数据已存储: {brightness_item}")
            
            return {
                'statusCode': 200,
                'body': json.dumps('组合设备状态数据已成功处理!')
            }
        
        # 处理单独指标消息
        else:
            # 处理亮度数据
            if 'screenBrightness' in event:
                brightness_item = {
                    'PK': partition_key,
                    'SK': f"BRIGHTNESS#{timestamp}",
                    'screenBrightness': event['screenBrightness'],
                    'timestamp': timestamp
                }
                table.put_item(Item=brightness_item)
                logger.info(f"单独亮度数据已存储: {brightness_item}")
                
                # 处理亮度控制请求
                if event.get('isControlRequest', False):
                    logger.info(f"发送亮度控制命令: {event['screenBrightness']}%")
                    iot_client.publish(
                        topic='AMS/brightness/control',
                        qos=1,
                        payload=json.dumps({'screenBrightness': event['screenBrightness']})
                    )
            
            # 处理WiFi数据
            elif 'wifiStatus' in event:
                wifi_item = {
                    'PK': partition_key,
                    'SK': f"WIFI#{timestamp}",
                    'wifiStatus': event['wifiStatus'],
                    'connectedSSID': event.get('connectedSSID', 'Unknown'),
                    'timestamp': timestamp
                }
                table.put_item(Item=wifi_item)
                logger.info(f"单独WiFi数据已存储: {wifi_item}")
            
            # 处理蓝牙数据
            elif 'bluetoothStatus' in event:
                bluetooth_item = {
                    'PK': partition_key,
                    'SK': f"BLUETOOTH#{timestamp}",
                    'bluetoothStatus': event['bluetoothStatus'],
                    'pairedDevicesCount': event.get('pairedDevicesCount', 0),
                    'timestamp': timestamp
                }
                table.put_item(Item=bluetooth_item)
                logger.info(f"单独蓝牙数据已存储: {bluetooth_item}")
            
            # 处理蓝牙设备连接/断开状态
            elif 'deviceName' in event and 'status' in event:
                device_status_item = {
                    'PK': partition_key,
                    'SK': f"DEVICE_STATUS#{timestamp}",
                    'deviceName': event['deviceName'],
                    'status': event['status'],
                    'timestamp': timestamp
                }
                table.put_item(Item=device_status_item)
                logger.info(f"设备状态已存储: {device_status_item}")
            
            return {
                'statusCode': 200,
                'body': json.dumps('单独指标数据已成功处理!')
            }
    
    except Exception as e:
        logger.error(f"处理事件时出错: {str(e)}")
        logger.error(f"事件内容: {json.dumps(event)}")
        
        # 尝试保存错误记录
        try:
            error_record = {
                'PK': f"ERROR#{str(uuid.uuid4())}",
                'SK': f"ERROR#{datetime.now().isoformat()}",
                'error_message': str(e),
                'event_data': json.dumps(event)
            }
            table.put_item(Item=error_record)
        except Exception as inner_e:
            logger.error(f"保存错误记录时失败: {str(inner_e)}")
        
        return {
            'statusCode': 500,
            'body': json.dumps(f"错误: {str(e)}")
        }