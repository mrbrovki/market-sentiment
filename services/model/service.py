import json
import time
from pathlib import Path
from config import Config
from data_loader import DataLoader
from kafka_io import KafkaIO
from trainer import ModelTrainer
from features import FeatureEngineering
from utils.logger import get_logger
from typing import Dict, List
logger = get_logger(__name__)

def run_service():
    config = Config()
    loader = DataLoader()
    consumer = KafkaIO.initialize_kafka_consumer(config)
    trainer = ModelTrainer(config)

    asset_names:List = list()
    messages:Dict = dict()
    asset_prices:Dict = dict()
    events:Dict = dict()

    last_msg_time = time.time()
    last_train_time = time.time()
    trained_once = False

    logger.info("Kafka training service started.")

    try:
        while True:
            msg_batch = consumer.poll(timeout_ms=1000)
            for _, records in msg_batch.items():
                for record in records:
                    asset_name = record.value.get("asset")
                    if asset_name not in asset_names:
                        asset_names.append(asset_name) 

                    if(messages.get(asset_name) is None):
                        messages[asset_name] = []
                    messages[asset_name].append(record.value)

                    last_msg_time = time.time()

            idle = time.time() - last_msg_time
            since_train = time.time() - last_train_time

            if not trained_once and idle >= config.idle_timeout:
                logger.info("Idle timeout reached. Starting training process.")

                # Set events dataframes for each asset
                for asset_name, arr in messages.items():
                    events_df = loader.events_from_kafka_messages(arr)
                    events[asset_name] = FeatureEngineering.inject_blank_events(events_df, freq='D')

                
                # Set price dataframes for each asset
                for asset_name in asset_names:
                    first_date = events[asset_name]['dt'].iat[0].strftime('%Y-%m-%d')
                    last_date = events[asset_name]['dt'].iat[-1].strftime('%Y-%m-%d')
                    price_df = loader.load_prices(asset_name, first_date, last_date, interval='1d', resample_freq='1d')
                    asset_prices[asset_name] = price_df


                results = trainer.train(events, asset_prices, asset_names)
                
                if results.get("success"):
                    trainer.save(config.output_dir)
                    json.dump(results, open(Path(config.output_dir) / "latest_results.json", "w"), indent=2)
                    trained_once = True
                    last_train_time = time.time()
                else:
                    logger.error(f"Training failed: {results.get('error')}")
                    

            if trained_once and since_train >= config.retrain_interval:
                logger.info("Retraining interval reached. Resetting offset.")
                KafkaIO.reset_consumer_offset(consumer, config)
                messages.clear()
                trained_once = False
                last_msg_time = time.time()

            time.sleep(0.1)

    except KeyboardInterrupt:
        logger.info("Service interrupted.")
    finally:
        consumer.close()
