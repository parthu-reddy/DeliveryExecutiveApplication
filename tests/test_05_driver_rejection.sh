#!/bin/bash
set -e

echo "--- START: Driver Rejection Scenario ---"

echo "1. Onboarding Restaurant..."
REST_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d '{"name": "Driver Rejection Burger", "fssaiLicenseNumber": "1234567895", "gstin": "123456789012351", "pan": "ABCDE1239F", "cin": "U12345MH2023PTC123461", "bankAccountNumber": "1234567890", "ifscCode": "HDFC0001234", "lat": 12.9716, "lng": 77.5946}' http://localhost:8092/api/v1/restaurants/onboard)
REST_ID=$(echo $REST_RESPONSE | jq -r '.data.id')

echo "2. Adding Menu Item..."
MENU_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d '{"name": "Rejection Meal", "price": 150.00, "isAvailable": true}' http://localhost:8092/api/v1/restaurants/$REST_ID/catalog/items)
MENU_ID=$(echo $MENU_RESPONSE | jq -r '.data.id')

echo "3. Creating Customer in DB..."
CUST_PHONE="999$(printf "%07d" $RANDOM$RANDOM | cut -c1-7)"
CUST_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')
docker exec -i food_delivery_db psql -U postgres -d food_delivery -c "INSERT INTO customers (id, name, phone_number) VALUES ('$CUST_ID', 'Test Customer', '$CUST_PHONE');" > /dev/null

echo "4. Onboarding Delivery Executive..."
DEL_PHONE="888$(printf "%07d" $RANDOM$RANDOM | cut -c1-7)"
DEL_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d "{\"name\": \"Reject Exec\", \"phoneNumber\": \"$DEL_PHONE\", \"vehicleNumber\": \"KA01AB5678\"}" http://localhost:8092/api/delivery/onboard)
DEL_EXEC_ID=$(echo $DEL_RESPONSE | jq -r '.data.id')

echo "4.5. Making Delivery Executive 1 ONLINE and Setting Location..."
curl -s -X POST -H "Content-Type: application/json" -d "{\"driverId\": \"$DEL_EXEC_ID\", \"available\": true}" http://localhost:8092/api/delivery/status > /dev/null
curl -s -X POST -H "Content-Type: application/json" -d "[{\"driverId\": \"$DEL_EXEC_ID\", \"lat\": 12.9716, \"lng\": 77.5946, \"timestamp\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}]" http://localhost:8092/api/v1/delivery/telemetry/batch > /dev/null

echo "4.6. Onboarding Delivery Executive 2 (Fallback)..."
DEL_PHONE_2="888$(printf "%07d" $RANDOM$RANDOM | cut -c1-7)"
DEL_RESPONSE_2=$(curl -s -X POST -H "Content-Type: application/json" -d "{\"name\": \"Fallback Exec\", \"phoneNumber\": \"$DEL_PHONE_2\", \"vehicleNumber\": \"KA02CD1234\"}" http://localhost:8092/api/delivery/onboard)
DEL_EXEC_ID_2=$(echo $DEL_RESPONSE_2 | jq -r '.data.id')

echo "4.7. Making Delivery Executive 2 ONLINE and Setting Location..."
curl -s -X POST -H "Content-Type: application/json" -d "{\"driverId\": \"$DEL_EXEC_ID_2\", \"available\": true}" http://localhost:8092/api/delivery/status > /dev/null
curl -s -X POST -H "Content-Type: application/json" -d "[{\"driverId\": \"$DEL_EXEC_ID_2\", \"lat\": 12.9716, \"lng\": 77.5946, \"timestamp\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}]" http://localhost:8092/api/v1/delivery/telemetry/batch > /dev/null

echo "5. Placing Order..."
ORDER_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d "{
  \"customerId\": \"$CUST_ID\",
  \"restaurantId\": \"$REST_ID\",
  \"items\": [{\"menuItemId\": \"$MENU_ID\", \"quantity\": 2}]
}" http://localhost:8092/api/v1/orders)
ORDER_ID=$(echo $ORDER_RESPONSE | jq -r '.data.id')

echo "6. Simulating Payment Webhook..."
PAYLOAD="{\"event\": \"payment.success\",\"payload\": {\"payment\": {\"entity\": {\"order_id\": \"$ORDER_ID\",\"status\": \"captured\",\"amount\": 300.00}}}}"
SIG=$(python3 -c "import hmac, hashlib, base64; print(base64.b64encode(hmac.new(b'test_secret', b'$PAYLOAD', hashlib.sha256).digest()).decode())")
curl -s -X POST -H "Content-Type: application/json" -H "X-Vyapar-Signature: $SIG" -d "$PAYLOAD" http://localhost:8092/api/v1/webhooks/vyapar > /dev/null

echo "7. Polling for PAID Status..."
MAX_RETRIES=20
for (( i=1; i<=MAX_RETRIES; i++ )); do
  ORDER_STATUS=$(docker exec -i food_delivery_db psql -U postgres -d food_delivery -t -c "SELECT status FROM orders WHERE id = '$ORDER_ID';" | xargs)
  if [ "$ORDER_STATUS" == "PAID" ]; then break; fi
  sleep 2
done
if [ "$ORDER_STATUS" != "PAID" ]; then echo "FAIL: Expected PAID, got $ORDER_STATUS"; exit 1; fi

echo "8. Restaurant Accepts Order..."
curl -s -X POST http://localhost:8092/api/v1/restaurants/$REST_ID/fulfillment/orders/$ORDER_ID/accept > /dev/null

echo "9. Polling for DISPATCHED Status (Saga)..."
for (( i=1; i<=MAX_RETRIES; i++ )); do
  ORDER_STATUS=$(docker exec -i food_delivery_db psql -U postgres -d food_delivery -t -c "SELECT status FROM orders WHERE id = '$ORDER_ID';" | xargs)
  if [ "$ORDER_STATUS" == "DISPATCHED" ]; then break; fi
  sleep 2
done
if [ "$ORDER_STATUS" != "DISPATCHED" ]; then echo "FAIL: Expected DISPATCHED, got $ORDER_STATUS"; exit 1; fi

echo "10. Driver Rejects Ping..."
curl -s -X POST "http://localhost:8092/api/delivery/drivers/$DEL_EXEC_ID/orders/$ORDER_ID/reject" > /dev/null

echo "11. Verifying order gets assigned to Driver 2..."
sleep 5 # Wait for Saga to process the Outbox event and Redispatch
ORDER_STATUS=$(docker exec -i food_delivery_db psql -U postgres -d food_delivery -t -c "SELECT status FROM orders WHERE id = '$ORDER_ID';" | xargs)
ASSIGNED_DRIVER=$(docker exec -i food_delivery_db psql -U postgres -d food_delivery -t -c "SELECT delivery_executive_id FROM orders WHERE id = '$ORDER_ID';" | xargs)

if [ "$ORDER_STATUS" == "DISPATCHED" ] && [ "$ASSIGNED_DRIVER" == "$DEL_EXEC_ID_2" ]; then
    echo "Order correctly reassigned to Driver 2 ($ASSIGNED_DRIVER)."
else
    echo "FAIL: Expected DISPATCHED to $DEL_EXEC_ID_2, got status $ORDER_STATUS to driver $ASSIGNED_DRIVER"
    exit 1
fi

echo "--- SUCCESS: Driver Rejection and Redispatch Scenario Completed ---"
exit 0
