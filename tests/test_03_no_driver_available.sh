#!/bin/bash
set -e

echo "--- START: No Driver Available Scenario ---"

# Clear Redis locations to ensure no drivers are available
docker exec -i food_delivery_redis redis-cli FLUSHALL > /dev/null

echo "1. Onboarding Restaurant..."
REST_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d '{"name": "No Driver Burger", "fssaiLicenseNumber": "1234567892", "gstin": "123456789012347", "pan": "ABCDE1236F", "cin": "U12345MH2023PTC123458", "bankAccountNumber": "1234567890", "ifscCode": "HDFC0001234", "lat": 12.9716, "lng": 77.5946}' http://localhost:8092/api/v1/restaurants/onboard)
REST_ID=$(echo $REST_RESPONSE | jq -r '.data.id')

echo "2. Adding Menu Item..."
MENU_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d '{"name": "No Driver Meal", "price": 150.00, "isAvailable": true}' http://localhost:8092/api/v1/restaurants/$REST_ID/catalog/items)
MENU_ID=$(echo $MENU_RESPONSE | jq -r '.data.id')

echo "3. Creating Customer in DB..."
CUST_PHONE="999$(printf "%07d" $RANDOM$RANDOM | cut -c1-7)"
CUST_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')
docker exec -i food_delivery_db psql -U postgres -d food_delivery -c "INSERT INTO customers (id, name, phone_number) VALUES ('$CUST_ID', 'Test Customer', '$CUST_PHONE');" > /dev/null

echo "4. Placing Order..."
ORDER_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d "{
  \"customerId\": \"$CUST_ID\",
  \"restaurantId\": \"$REST_ID\",
  \"items\": [{\"menuItemId\": \"$MENU_ID\", \"quantity\": 2}]
}" http://localhost:8092/api/v1/orders)
ORDER_ID=$(echo $ORDER_RESPONSE | jq -r '.data.id')

echo "5. Simulating Payment Webhook..."
PAYLOAD="{\"event\": \"payment.success\",\"payload\": {\"payment\": {\"entity\": {\"order_id\": \"$ORDER_ID\",\"status\": \"captured\",\"amount\": 300.00}}}}"
SIG=$(python3 -c "import hmac, hashlib, base64; print(base64.b64encode(hmac.new(b'test_secret', b'$PAYLOAD', hashlib.sha256).digest()).decode())")
curl -s -X POST -H "Content-Type: application/json" -H "X-Vyapar-Signature: $SIG" -d "$PAYLOAD" http://localhost:8092/api/v1/webhooks/vyapar > /dev/null

echo "6. Polling for PAID Status..."
MAX_RETRIES=20
for (( i=1; i<=MAX_RETRIES; i++ )); do
  ORDER_STATUS=$(docker exec -i food_delivery_db psql -U postgres -d food_delivery -t -c "SELECT status FROM orders WHERE id = '$ORDER_ID';" | xargs)
  if [ "$ORDER_STATUS" == "PAID" ]; then break; fi
  sleep 2
done
if [ "$ORDER_STATUS" != "PAID" ]; then echo "FAIL: Expected PAID, got $ORDER_STATUS"; exit 1; fi

echo "7. Restaurant Accepts Order..."
curl -s -X POST http://localhost:8092/api/v1/restaurants/$REST_ID/fulfillment/orders/$ORDER_ID/accept > /dev/null

echo "8. Verifying order becomes DELIVERY_FAILED and refunds..."
sleep 15
ORDER_STATUS=$(docker exec -i food_delivery_db psql -U postgres -d food_delivery -t -c "SELECT status FROM orders WHERE id = '$ORDER_ID';" | xargs)

if [ "$ORDER_STATUS" == "DELIVERY_FAILED" ]; then
    echo "Order correctly moved to DELIVERY_FAILED state."
else
    echo "FAIL: Expected DELIVERY_FAILED, but got $ORDER_STATUS"
    exit 1
fi

echo "--- SUCCESS: No Driver Available Scenario Completed ---"
exit 0
