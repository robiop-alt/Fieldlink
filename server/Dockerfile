FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py db_persistence.py r2_storage.py pwa_assets.py drone_zones.py planner_pdf.py planner_buildings.py population_ssb.py android_updates.py ./
# Check optional map modules inside the actual production image, not just locally.
RUN python -c "import population_ssb; assert callable(population_ssb.features_in_bbox)"
COPY static ./static
ENV PYTHONUNBUFFERED=1
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
