FROM python:3.10-slim
WORKDIR /app
RUN apt-get update && apt-get install -y \
 gcc \
 libgfortran5 \
 && rm -rf /var/lib/apt/lists/*

COPY . .
RUN pip install --upgrade pip
RUN pip install -r requirements.txt
RUN pip install kafka-python
CMD ["python", "app.py"]
