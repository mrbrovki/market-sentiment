import pandas as pd
import yfinance as yf
from utils.logger import get_logger

logger = get_logger(__name__)

class DataLoader:
    """Handle message parsing and price data loading."""

    @staticmethod
    def events_from_kafka_messages(messages):
        if not messages:
            return pd.DataFrame()

        df = pd.DataFrame(messages)
        ts_col = "timeStamp" if "timeStamp" in df.columns else "timestamp"
        df["timestamp_ms"] = pd.to_numeric(df[ts_col], errors="coerce")
        df["sentiment"] = df["score"] if "score" in df.columns else None

        df["dt"] = pd.to_datetime(df["timestamp_ms"], unit="ms", errors="coerce")
        df.dropna(subset=["dt"], inplace=True)

        df = df.set_index("dt").sort_index().reset_index()

        return df[["asset", "sentiment", "dt"]]

    @staticmethod
    def load_prices(ticker, start_date, end_date, interval, resample_freq):
        """
        Load price data from yfinance.
        
        Args:
            ticker (str): Stock ticker symbol (e.g., 'AAPL', 'BTC-USD')
            start_date (str/int): Start date in format 'YYYY-MM-DD', datetime, or timestamp in milliseconds
            end_date (str/int): End date in format 'YYYY-MM-DD', datetime, or timestamp in milliseconds
            interval (str): Data interval - valid values: 1m, 2m, 5m, 15m, 30m, 60m, 90m, 1h, 1d, 5d, 1wk, 1mo, 3mo
            resample_freq (str): Frequency to resample data (e.g., '3h', '1d', '4h')
        
        Returns:
            pd.DataFrame: DataFrame with columns ['dt', 'open', 'close', 'return']
        """
        # Convert timestamps in milliseconds to datetime if needed
        if isinstance(start_date, (int, float)):
            start_date = pd.to_datetime(start_date, unit='ms')
        if isinstance(end_date, (int, float)):
            end_date = pd.to_datetime(end_date, unit='ms')
        
        logger.info(f"Loading price data for {ticker} from {start_date} to {end_date}")
        
        # Download data from yfinance
        df = yf.download(ticker, start=start_date, end=end_date, interval=interval, progress=False)
        
        if df.empty:
            logger.warning(f"No data retrieved for {ticker}")
            return pd.DataFrame(columns=['dt', 'open', 'close', 'return'])
        
        # Reset index to make datetime a column
        df = df.reset_index()

        # Remove multi-level column names if they exist
        if isinstance(df.columns, pd.MultiIndex):
            df.columns = df.columns.droplevel(1)
        
        # Rename the datetime column to 'dt'
        date_col = 'Datetime' if 'Datetime' in df.columns else 'Date'
        df = df.rename(columns={date_col: 'dt', 'Open': 'open', 'Close': 'close'})
        
        # Select relevant columns
        df = df[["dt", "open", "close"]].dropna()
        

        # Resample if needed
        df = (
            df.set_index("dt")
              .resample(resample_freq)
              .agg({"open": "first", "close": "last"})
              .sort_index()
              .dropna()
              .reset_index()
        )
        
        # Calculate returns
        df["return"] = df["close"] / df["open"] - 1
        
        logger.info(f"Loaded {len(df)} rows of price data")
        return df