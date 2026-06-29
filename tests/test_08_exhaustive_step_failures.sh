#!/bin/bash
set -e

echo "--- START: Exhaustive Step Failures Scenario ---"

echo "1. Onboarding Restaurant..."
REST_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d '{"name": "Exhaustive Failures Burger", "fssaiLicenseNumber": "1234567898", "gstin": "123456789012354", "pan": "ABCDE1242F", "cin": "U12345MH2023PTC123464", "bankAccountNumber": "1234567890", "ifscCode": "HDFC0001234", "lat": 12.9716, "lng": 77.5946}' http://localhost:8092/api/v1/restaurants/onboard)
REST_ID=$(echo $REST_RESPONSE | jq -r '.data.id')

echo "2. Adding Menu Item..."
MENU_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d '{"name": "Fail Meal", "price": 150.00, "isAvailable": true}' http://localhost:8092/api/v1/restaurants/$REST_ID/catalog/items)
MENU_ID=$(echo $MENU_RESPONSE | jq -r '.data.id')

echo "3. Creating Customer in DB..."
CUST_PHONE="999$(printf "%07d" $RANDOM$RANDOM | cut -c1-7)"
CUST_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')
docker exec -i food_delivery_db psql -U postgres -d food_delivery -c "INSERT INTO customers (id, name, phone_number) VALUES ('$CUST_ID', 'Test Customer', '$CUST_PHONE');" > /dev/null

echo "4. Onboarding Delivery Executive 1..."
DEL_PHONE_1="888$(printf "%07d" $RANDOM$RANDOM | cut -c1-7)"
DEL_RESPONSE_1=$(curl -s -X POST -H "Content-Type: application/json" -d "{\"name\": \"Exec 1\", \"phoneNumber\": \"$DEL_PHONE_1\", \"vehicleNumber\": \"KA01AB8881\"}" http://localhost:8092/api/delivery/onboard)
DEL_EXEC_ID_1=$(echo $DEL_RESPONSE_1 | jq -r '.data.id')

echo "4.1. Onboarding Delivery Executive 2..."
DEL_PHONE_2="888$(printf "%07d" $RANDOM$RANDOM | cut -c1-7)"
DEL_RESPONSE_2=$(curl -s -X POST -H "Content-Type: application/json" -d "{\"name\": \"Exec 2\", \"phoneNumber\": \"$DEL_PHONE_2\", \"vehicleNumber\": \"KA01AB8882\"}" http://localhost:8092/api/delivery/onboard)
DEL_EXEC_ID_2=$(echo $DEL_RESPONSE_2 | jq -r '.data.id')

curl -s -X POST -H "Content-Type: application/json" -d "{\"driverId\": \"$DEL_EXEC_ID_1\", \"available\": true}" http://localhost:8092/api/delivery/status > /dev/null
curl -s -X POST -H "Content-Type: application/json" -d "[{\"driverId\": \"$DEL_EXEC_ID_1\", \"lat\": 12.9716, \"lng\": 77.5946, \"timestamp\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}]" http://localhost:8092/api/v1/delivery/telemetry/batch > /dev/null

echo "=========================================================="
echo "FAILURE STEP 1: Invalid Quantity (Creation)"
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST -H "Content-Type: application/json" -d "{
  \"customerId\": \"$CUST_ID\",
  \"restaurantId\": \"$REST_ID\",
  \"items\": [{\"menuItemId\": \"$MENU_ID\", \"quantity\": -5}]
}" http://localhost:8092/api/v1/orders)
if [ "$HTTP_STATUS" != "500" ] && [ "$HTTP_STATUS" != "400" ]; then
    echo "FAIL: Expected HTTP error for negative quantity, got $HTTP_STATUS"
    exit 1
else
    echo "Successfully caught validation error for negative quantity (HTTP $HTTP_STATUS)"
fi

echo "=========================================================="
echo "Placing Valid Order..."
ORDER_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d "{
  \"customerId\": \"$CUST_ID\",
  \"restaurantId\": \"$REST_ID\",
  \"items\": [{\"menuItemId\": \"$MENU_ID\", \"quantity\": 2}]
}" http://localhost:8092/api/v1/orders)
ORDER_ID=$(echo $ORDER_RESPONSE | jq -r '.data.id')

echo "=========================================================="
echo "FAILURE STEP 2: Invalid Webhook Signature (Payment)"
PAYLOAD="{\"event\": \"payment.success\",\"payload\": {\"payment\": {\"entity\": {\"order_id\": \"$ORDER_ID\",\"status\": \"captured\",\"amount\": 300.00}}}}"
INVALID_SIG="invalid_signature_xyz"
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST -H "Content-Type: application/json" -H "X-Vyapar-Signature: $INVALID_SIG" -d "$PAYLOAD" http://localhost:8092/api/v1/webhooks/vyapar)
if [ "$HTTP_STATUS" != "401" ]; then
    echo "FAIL: Expected 401 for invalid signature, got $HTTP_STATUS"
    exit 1
else
    echo "Successfully caught invalid signature (HTTP $HTTP_STATUS)"
fi

echo "Simulating VALID Payment Webhook..."
SIG=$(python3 -c "import hmac, hashlib, base64; print(base64.b64encode(hmac.new(b'test_secret', b'$PAYLOAD', hashlib.sha256).digest()).decode())")
curl -s -X POST -H "Content-Type: application/json" -H "X-Vyapar-Signature: $SIG" -d "$PAYLOAD" http://localhost:8092/api/v1/webhooks/vyapar > /dev/null

echo "Polling for PAID Status..."
MAX_RETRIES=20
for (( i=1; i<=MAX_RETRIES; i++ )); do
  ORDER_STATUS=$(docker exec -i food_delivery_db psql -U postgres -d food_delivery -t -c "SELECT status FROM orders WHERE id = '$ORDER_ID';" | xargs)
  if [ "$ORDER_STATUS" == "PAID" ]; then break; fi
  sleep 2
done
if [ "$ORDER_STATUS" != "PAID" ]; then echo "FAIL: Expected PAID, got $ORDER_STATUS"; exit 1; fi

echo "=========================================================="
echo "FAILURE STEP 3: Restaurant double accept (Fulfillment)"
curl -s -X POST http://localhost:8092/api/v1/restaurants/$REST_ID/fulfillment/orders/$ORDER_ID/accept > /dev/null

HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8092/api/v1/restaurants/$REST_ID/fulfillment/orders/$ORDER_ID/accept)
if [ "$HTTP_STATUS" != "500" ] && [ "$HTTP_STATUS" != "400" ]; then
    echo "FAIL: Expected error for double accept, got $HTTP_STATUS"
    exit 1
else
    echo "Successfully caught double accept attempt (HTTP $HTTP_STATUS)"
fi

echo "Polling for DISPATCHED Status (Saga)..."
for (( i=1; i<=MAX_RETRIES; i++ )); do
  ORDER_STATUS=$(docker exec -i food_delivery_db psql -U postgres -d food_delivery -t -c "SELECT status FROM orders WHERE id = '$ORDER_ID';" | xargs)
  if [ "$ORDER_STATUS" == "DISPATCHED" ]; then break; fi
  sleep 2
done
if [ "$ORDER_STATUS" != "DISPATCHED" ]; then echo "FAIL: Expected DISPATCHED, got $ORDER_STATUS"; exit 1; fi

echo "=========================================================="
echo "FAILURE STEP 4: Unauthorized Driver Actions (Logistics)"

# Exec 2 tries to reject Exec 1's order
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "http://localhost:8092/api/delivery/drivers/$DEL_EXEC_ID_2/orders/$ORDER_ID/reject")
if [ "$HTTP_STATUS" != "500" ] && [ "$HTTP_STATUS" != "400" ]; then
    echo "FAIL: Expected error for unauthorized driver reject, got $HTTP_STATUS"
    exit 1
else
    echo "Successfully caught unauthorized driver reject attempt (HTTP $HTTP_STATUS)"
fi

echo "Restaurant marks READY_FOR_PICKUP..."
curl -s -X POST http://localhost:8092/api/v1/restaurants/$REST_ID/fulfillment/orders/$ORDER_ID/ready > /dev/null

# Exec 2 tries to accept Exec 1's order
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "http://localhost:8092/api/delivery/drivers/$DEL_EXEC_ID_2/orders/$ORDER_ID/accept")
if [ "$HTTP_STATUS" != "500" ] && [ "$HTTP_STATUS" != "400" ]; then
    echo "FAIL: Expected error for unauthorized driver accept, got $HTTP_STATUS"
    exit 1
else
    echo "Successfully caught unauthorized driver accept attempt (HTTP $HTTP_STATUS)"
fi

echo "--- SUCCESS: Exhaustive Step Failures Scenario Completed ---"
exit 0
