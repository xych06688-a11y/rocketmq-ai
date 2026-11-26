"""Fetch a fresh, attributable Open-Meteo snapshot for this coursework."""
import argparse
import datetime
import json
import urllib.parse
import urllib.request
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--latitude', type=float, default=39.9042)
parser.add_argument('--longitude', type=float, default=116.4074)
parser.add_argument('--output', default='weather-snapshot.json')
args = parser.parse_args()
if not (-90 <= args.latitude <= 90 and -180 <= args.longitude <= 180):
    parser.error('Coordinates are outside their valid ranges')
query = urllib.parse.urlencode({
    'latitude': args.latitude, 'longitude': args.longitude,
    'current': 'temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m',
    'daily': 'weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max',
    'timezone': 'Asia/Shanghai', 'forecast_days': 3,
})
url = 'https://api.open-meteo.com/v1/forecast?' + query
with urllib.request.urlopen(url, timeout=40) as response:
    forecast = json.load(response)
snapshot = {
    'source': url,
    'retrievedAt': datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00', 'Z'),
    'requestedLatitude': args.latitude, 'requestedLongitude': args.longitude,
    'retrievalMode': 'actual-HTTPS-fetch-using-refresh_weather.py',
    'forecast': forecast,
}
Path(args.output).write_text(json.dumps(snapshot, ensure_ascii=False, indent=2), encoding='utf-8')
print('Saved real weather data to', args.output)
