#!/bin/bash
set -e

echo "1. Creating Restaurant..."
REST_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d '{"name": "E2E Burger", "fssaiLicenseNumber": "1234567890", "gstin": "123456789012345", "pan": "ABCDE1234F", "cin": "U12345MH2023PTC123456", "lat": 12.9716, "lng": 77.5946}' http://localhost:8092/api/v1/restaurants/onboard)
echo $REST_RESPONSE
REST_ID=$(echo $REST_RESPONSE | jq -r '.data.id')
echo "Restaurant ID: $REST_ID"

echo "2. Adding Menu Item..."
MENU_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d '{"name": "Veggie Burger", "price": 150.00, "isAvailable": true}' http://localhost:8092/api/v1/restaurants/$REST_ID/catalog/items)
echo $MENU_RESPONSE
MENU_ID=$(echo $MENU_RESPONSE | jq -r '.data.id')
echo "Menu Item ID: $MENU_ID"

echo "3. Creating Customer in DB..."
# Generate a random 10-digit phone number starting with 999 to avoid unique constraint violations across test runs
CUST_PHONE="999$(printf "%07d" $RANDOM$RANDOM | cut -c1-7)"
CUST_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')
docker exec food_delivery_db psql -U postgres -d food_delivery -c "INSERT INTO customers (id, name, phone_number) VALUES ('$CUST_ID', 'Test Customer', '$CUST_PHONE');"
echo "Customer ID: $CUST_ID, Phone: $CUST_PHONE"

echo "4. Creating Delivery Executive in DB..."
DEL_PHONE="888$(printf "%07d" $RANDOM$RANDOM | cut -c1-7)"
DEL_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d "{\"name\": \"Test Exec\", \"phoneNumber\": \"$DEL_PHONE\", \"vehicleNumber\": \"KA01AB1234\"}" http://localhost:8092/api/delivery/onboard)
echo $DEL_RESPONSE
DEL_EXEC_ID=$(echo $DEL_RESPONSE | jq -r '.data.id')
echo "Delivery Executive ID: $DEL_EXEC_ID, Phone: $DEL_PHONE"

echo "4.5. Making Delivery Executive ONLINE and Setting Location..."
curl -s -X POST -H "Content-Type: application/json" -d "{\"driverId\": \"$DEL_EXEC_ID\", \"available\": true}" http://localhost:8092/api/delivery/status
curl -s -X POST -H "Content-Type: application/json" -d "[{\"driverId\": \"$DEL_EXEC_ID\", \"lat\": 12.9716, \"lng\": 77.5946, \"timestamp\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}]" http://localhost:8092/api/v1/delivery/telemetry/batch
echo ""

echo "5. Creating Order..."
ORDER_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d "{
  \"customerId\": \"$CUST_ID\",
  \"restaurantId\": \"$REST_ID\",
  \"items\": [
    {
      \"menuItemId\": \"$MENU_ID\",
      \"quantity\": 2
    }
  ]
}" http://localhost:8092/api/v1/orders)
echo $ORDER_RESPONSE
ORDER_ID=$(echo $ORDER_RESPONSE | jq -r '.data.id')
echo "Order ID: $ORDER_ID"

echo "6. Simulating Payment Webhook..."
PAYLOAD="{\"event\": \"payment.success\",\"payload\": {\"payment\": {\"entity\": {\"order_id\": \"$ORDER_ID\",\"status\": \"captured\",\"amount\": 300.00}}}}"
SIG=$(python3 -c "import hmac, hashlib, base64; print(base64.b64encode(hmac.new(b'test_secret', b'$PAYLOAD', hashlib.sha256).digest()).decode())")
WEBHOOK_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -H "X-Vyapar-Signature: $SIG" -d "$PAYLOAD" http://localhost:8092/api/v1/webhooks/vyapar)
echo $WEBHOOK_RESPONSE

echo "7. Waiting for Payment to be processed by Saga..."
# Poll for status 'PAID' instead of a hardcoded sleep to avoid race conditions
MAX_RETRIES=15
for (( i=1; i<=MAX_RETRIES; i++ ))
do
  ORDER_STATUS=$(docker exec -i food_delivery_db psql -U postgres -d food_delivery -t -c "SELECT status FROM orders WHERE id = '$ORDER_ID';" | xargs)
  if [ "$ORDER_STATUS" == "PAID" ]; then
    echo "Order successfully transitioned to PAID."
    break
  fi
  echo "Order status is '$ORDER_STATUS' (attempt $i/$MAX_RETRIES). Waiting 2 seconds..."
  sleep 2
done

if [ "$ORDER_STATUS" != "PAID" ]; then
  echo "Order failed to transition to PAID within time limit. Exiting."
  exit 1
fi

echo "8. Simulating Restaurant Acceptance..."
ACCEPT_RESPONSE=$(curl -s -X POST http://localhost:8092/api/v1/restaurants/$REST_ID/fulfillment/orders/$ORDER_ID/accept)
echo $ACCEPT_RESPONSE

echo "9. Waiting for Saga to dispatch driver..."
# Poll for status 'DISPATCHED'
for (( i=1; i<=MAX_RETRIES; i++ ))
do
  ORDER_STATUS=$(docker exec -i food_delivery_db psql -U postgres -d food_delivery -t -c "SELECT status FROM orders WHERE id = '$ORDER_ID';" | xargs)
  if [ "$ORDER_STATUS" == "DISPATCHED" ]; then
    echo "Order successfully transitioned to DISPATCHED."
    break
  fi
  echo "Order status is '$ORDER_STATUS' (attempt $i/$MAX_RETRIES). Waiting 2 seconds..."
  sleep 2
done

if [ "$ORDER_STATUS" != "DISPATCHED" ]; then
  echo "Order failed to transition to DISPATCHED within time limit."
fi

echo "10. Checking Order Status (Should be DISPATCHED with Driver ID)..."
docker exec -i food_delivery_db psql -U postgres -d food_delivery -c "SELECT id, status, delivery_executive_id FROM orders WHERE id = '$ORDER_ID';"
