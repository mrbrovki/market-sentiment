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
    # Load configuration and initialize components
    config = Config()
    loader = DataLoader()
    trainer = ModelTrainer(config)

    # Initialize Kafka consumer and producer
    consumer = KafkaIO.initialize_kafka_consumer(config)
    producer = KafkaIO.initialize_kafka_producer(config)


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

            if ((not trained_once and idle >= config.IDLE_TEMOUT) or 
                (trained_once and since_train >= config.RETRAIN_INTERVAL and idle >= config.IDLE_TEMOUT)):
                logger.info("Idle timeout reached. Starting training process.")

                # Set events dataframes for each asset, injecting blank events where necessary and sort
                for asset_name, arr in messages.items():
                    events_df = loader.events_from_kafka_messages(arr)
                    events_df = FeatureEngineering.weighted_model_score(events_df)
                    events[asset_name] = FeatureEngineering.inject_blank_events(events_df, freq='D')

                
                # Set price dataframes for each asset
                for asset_name in asset_names:
                    first_date = events[asset_name]['dt'].iat[0].strftime('%Y-%m-%d')
                    last_date = events[asset_name]['dt'].iat[-1].strftime('%Y-%m-%d')
                    price_df = loader.load_prices(asset_name, first_date, last_date, interval='1d', resample_freq='1d')
                    asset_prices[asset_name] = price_df


                results = trainer.train(events, asset_prices, asset_names)
                
                if results.get("success"):
                    trainer.save(config.OUTPUT_DIR)
                    json.dump(results, open(Path(config.OUTPUT_DIR) / "latest_results.json", "w"), indent=2)
                    trained_once = True
                    last_train_time = time.time()
                    producer.send(
                        config.OUTPUT_TOPIC,
                        value=results
                    )

                    #send decayed scores to kafka
                    for asset in asset_names:
                        events_df = events[asset].copy()
                        # Apply decay
                        decayed_df = FeatureEngineering.apply_decay(events_df, trainer.best_optuna_params["lambdaDenom"])
                        decayed_df.dropna(inplace=True)

                        #iterate through rows and send to kafka
                        for _, row in decayed_df.iterrows():
                            producer.send(
                                config.DECAYED_SCORES_TOPIC,
                                key=asset,
                                value={
                                    "asset": asset,
                                    "timestamp": int(row["dt"].timestamp() * 1000),
                                    "lastTrainTime": int(last_train_time * 1000),
                                    "decayedScore": row["decayed_score"]
                                }
                            )

                else:
                    logger.error(f"Training failed: {results.get('error')}")

            else:
                # if there is a model already trained, make predictions on new data
                if trained_once:
                    for _, records in msg_batch.items():
                        for record in records:
                            asset_name = record.value.get("asset")
                            

                    predictions = trainer.predict(events, asset_prices, asset_names)
            time.sleep(0.1)

    except KeyboardInterrupt:
        logger.info("Service interrupted.")
    finally:
        consumer.close()
