import numpy as np
from sklearn.metrics import f1_score, make_scorer

class TradingScorer:
    """Custom scorer for trading signals."""

    @staticmethod
    def score(y_true, y_pred):
        mask = y_true != 0
        if mask.sum() == 0:
            return 0.0
        return f1_score(
            y_true[mask],
            y_pred[mask],
            labels=[1, 2],
            average="weighted",
            zero_division=0,
        )
    
    @staticmethod
    def f1_score(y_true, y_pred):
        return f1_score(
            y_true,
            y_pred,
            labels=[0, 1, 2],
            average="weighted",
            zero_division=0,
        )

    @classmethod
    def get_scorer(cls):
        return make_scorer(cls.f1_score)
