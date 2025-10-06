import threading
import time
from config import logger, MAX_RPD, MAX_RPM

class RateLimiterThread(threading.Thread):
    def __init__(self, rateLimitState):
        super().__init__(daemon=True)
        self.rateLimitState = rateLimitState

    def run(self):
        while True:
            now = time.time()
            with self.rateLimitState.rate_limit_lock:
                if now - self.rateLimitState.last_rpm_reset >= 60:
                    self.rateLimitState.resetRPM()
                    logger.debug("Reset RPM counter")

                if now - self.rateLimitState.last_rpd_reset >= 86400:
                    self.rateLimitState.resetRPD()
                    logger.debug("Reset RPD counter")
            time.sleep(10)

class RateLimitState:
    def __init__(self):
        self.current_rpm, self.current_rpd = MAX_RPM, MAX_RPD
        self.rate_limit_lock = threading.Lock()
        self.last_rpm_reset = time.time()
        self.last_rpd_reset = time.time()

    def can_consume_batch(self):
        with self.rate_limit_lock:
            if self.current_rpm > 0 and self.current_rpd > 0:
                self.useToken()
                return True
            return False
    
    def resetRPD(self):
        self.current_rpd = MAX_RPD
        self.last_rpd_reset = time.time()
    
    def resetRPM(self):
        self.current_rpm = MAX_RPM
        self.last_rpm_reset = time.time()
    
    def useToken(self):
            self.current_rpd -= 1
            self.current_rpm -= 1
