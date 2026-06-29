#!/bin/bash
set -e

echo "--- START: Test 9 - New Theoretical Edge Cases ---"

echo "1. Onboarding Restaurant..."
REST_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d '{"name": "Edge Case Diner", "fssaiLicenseNumber": "1234567899", "gstin": "123456789012355", "pan": "ABCDE1242G", "cin": "U12345MH2023PTC123465", "bankAccountNumber": "1234567890", "ifscCode": "HDFC0001234", "lat": 12.9716, "lng": 77.5946}' http://localhost:8092/api/v1/restaurants/onboard)
REST_ID=$(echo $REST_RESPONSE | jq -r '.data.id')

echo "2. Adding Menu Item..."
MENU_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d '{"name": "Edge Burger", "price": 200.00, "isAvailable": true}' http://localhost:8092/api/v1/restaurants/$REST_ID/catalog/items)
MENU_ID=$(echo $MENU_RESPONSE | jq -r '.data.id')

echo "3. Creating Customer in DB..."
CUST_PHONE="999$(printf "%07d" $RANDOM$RANDOM | cut -c1-7)"
CUST_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')
docker exec -i food_delivery_db psql -U postgres -d food_delivery -c "INSERT INTO customers (id, name, phone_number) VALUES ('$CUST_ID', 'Edge Customer', '$CUST_PHONE');" > /dev/null

echo "4. Onboarding Delivery Executive..."
DEL_PHONE="888$(printf "%07d" $RANDOM$RANDOM | cut -c1-7)"
DEL_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d "{\"name\": \"Edge Driver\", \"phoneNumber\": \"$DEL_PHONE\", \"vehicleNumber\": \"KA01AB8889\"}" http://localhost:8092/api/delivery/onboard)
DEL_EXEC_ID=$(echo $DEL_RESPONSE | jq -r '.data.id')
curl -s -X POST -H "Content-Type: application/json" -d "{\"driverId\": \"$DEL_EXEC_ID\", \"available\": true}" http://localhost:8092/api/delivery/status > /dev/null
curl -s -X POST -H "Content-Type: application/json" -d "[{\"driverId\": \"$DEL_EXEC_ID\", \"lat\": 12.9716, \"lng\": 77.5946, \"timestamp\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}]" http://localhost:8092/api/v1/delivery/telemetry/batch > /dev/null


echo "=========================================================="
echo "EDGE CASE 1: Restaurant Post-Acceptance Cancellation"
ORDER_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d "{\"customerId\": \"$CUST_ID\",\"restaurantId\": \"$REST_ID\",\"items\": [{\"menuItemId\": \"$MENU_ID\", \"quantity\": 1}]}" http://localhost:8092/api/v1/orders)
ORDER_ID_1=$(echo $ORDER_RESPONSE | jq -r '.data.id')

PAYLOAD="{\"event\": \"payment.success\",\"payload\": {\"payment\": {\"entity\": {\"order_id\": \"$ORDER_ID_1\",\"status\": \"captured\",\"amount\": 200.00}}}}"
SIG=$(python3 -c "import hmac, hashlib, base64; print(base64.b64encode(hmac.new(b'test_secret', b'$PAYLOAD', hashlib.sha256).digest()).decode())")
curl -s -X POST -H "Content-Type: application/json" -H "X-Vyapar-Signature: $SIG" -d "$PAYLOAD" http://localhost:8092/api/v1/webhooks/vyapar > /dev/null

sleep 2 # wait for paid
curl -s -X POST http://localhost:8092/api/v1/restaurants/$REST_ID/fulfillment/orders/$ORDER_ID_1/accept > /dev/null

echo "Waiting for driver to be dispatched..."
for i in {1..10}; do
    DISPATCHED_DRIVER_ID_1=$(docker exec -i food_delivery_db psql -U postgres -d food_delivery -t -c "SELECT delivery_executive_id FROM orders WHERE id = '$ORDER_ID_1';" | xargs)
    if [ "$DISPATCHED_DRIVER_ID_1" != "" ] && [ "$DISPATCHED_DRIVER_ID_1" != "null" ]; then
        break
    fi
    sleep 1
done

# Now Restaurant cancels
echo "Restaurant cancelling order..."
curl -s -X POST http://localhost:8092/api/v1/restaurants/$REST_ID/fulfillment/orders/$ORDER_ID_1/cancel > /dev/null

sleep 2
ORDER_STATUS=$(docker exec -i food_delivery_db psql -U postgres -d food_delivery -t -c "SELECT status FROM orders WHERE id = '$ORDER_ID_1';" | xargs)
if [ "$ORDER_STATUS" != "CANCELLED_BY_RESTAURANT" ]; then echo "FAIL: Expected CANCELLED_BY_RESTAURANT, got $ORDER_STATUS"; exit 1; fi
echo "Successfully cancelled by restaurant."


echo "=========================================================="
echo "EDGE CASE 2: Driver marks DELIVERY_FAILED"
echo "Pushing telemetry to make driver available..."
curl -s -X POST -H "Content-Type: application/json" -d "[{\"driverId\": \"$DEL_EXEC_ID\", \"lat\": 12.9716, \"lng\": 77.5946, \"timestamp\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}]" http://localhost:8092/api/v1/delivery/telemetry/batch > /dev/null

ORDER_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d "{\"customerId\": \"$CUST_ID\",\"restaurantId\": \"$REST_ID\",\"items\": [{\"menuItemId\": \"$MENU_ID\", \"quantity\": 1}]}" http://localhost:8092/api/v1/orders)
ORDER_ID_2=$(echo $ORDER_RESPONSE | jq -r '.data.id')

PAYLOAD="{\"event\": \"payment.success\",\"payload\": {\"payment\": {\"entity\": {\"order_id\": \"$ORDER_ID_2\",\"status\": \"captured\",\"amount\": 200.00}}}}"
SIG=$(python3 -c "import hmac, hashlib, base64; print(base64.b64encode(hmac.new(b'test_secret', b'$PAYLOAD', hashlib.sha256).digest()).decode())")
curl -s -X POST -H "Content-Type: application/json" -H "X-Vyapar-Signature: $SIG" -d "$PAYLOAD" http://localhost:8092/api/v1/webhooks/vyapar > /dev/null

sleep 2 # wait for paid
curl -s -X POST http://localhost:8092/api/v1/restaurants/$REST_ID/fulfillment/orders/$ORDER_ID_2/accept > /dev/null

echo "Waiting for driver to be dispatched..."
for i in {1..10}; do
    DISPATCHED_DRIVER_ID=$(docker exec -i food_delivery_db psql -U postgres -d food_delivery -t -c "SELECT delivery_executive_id FROM orders WHERE id = '$ORDER_ID_2';" | xargs)
    if [ "$DISPATCHED_DRIVER_ID" != "" ] && [ "$DISPATCHED_DRIVER_ID" != "null" ]; then
        break
    fi
    sleep 1
done

if [ "$DISPATCHED_DRIVER_ID" == "" ] || [ "$DISPATCHED_DRIVER_ID" == "null" ]; then
    echo "FAIL: Driver was not dispatched."
    exit 1
fi

curl -s -X POST http://localhost:8092/api/v1/restaurants/$REST_ID/fulfillment/orders/$ORDER_ID_2/ready > /dev/null
curl -s -X POST http://localhost:8092/api/delivery/drivers/$DISPATCHED_DRIVER_ID/orders/$ORDER_ID_2/accept > /dev/null

echo "Driver marking DELIVERY_FAILED..."
curl -s -X POST -H "Content-Type: application/json" -d "{\"status\": \"DELIVERY_FAILED\"}" http://localhost:8092/api/delivery/drivers/$DISPATCHED_DRIVER_ID/orders/$ORDER_ID_2/status > /dev/null

sleep 2
ORDER_STATUS=$(docker exec -i food_delivery_db psql -U postgres -d food_delivery -t -c "SELECT status FROM orders WHERE id = '$ORDER_ID_2';" | xargs)
if [ "$ORDER_STATUS" != "DELIVERY_FAILED" ]; then echo "FAIL: Expected DELIVERY_FAILED, got $ORDER_STATUS"; exit 1; fi
echo "Successfully marked DELIVERY_FAILED."

echo "Verifying Refund for DELIVERY_FAILED..."
sleep 5 # Wait for outbox poller and saga
PAYMENT_INTENT_STATUS=$(docker exec -i food_delivery_db psql -U postgres -d food_delivery -t -c "SELECT status FROM payment_intents WHERE internal_order_id = '$ORDER_ID_2';" | xargs)
if [ "$PAYMENT_INTENT_STATUS" != "REFUNDED" ]; then echo "FAIL: Expected REFUNDED, got $PAYMENT_INTENT_STATUS"; exit 1; fi
echo "Refund processed successfully."


echo "=========================================================="
echo "EDGE CASE 3: Driver ping Timeout Simulator"
echo "Pushing telemetry to make driver available..."
curl -s -X POST -H "Content-Type: application/json" -d "[{\"driverId\": \"$DEL_EXEC_ID\", \"lat\": 12.9716, \"lng\": 77.5946, \"timestamp\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}]" http://localhost:8092/api/v1/delivery/telemetry/batch > /dev/null

ORDER_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d "{\"customerId\": \"$CUST_ID\",\"restaurantId\": \"$REST_ID\",\"items\": [{\"menuItemId\": \"$MENU_ID\", \"quantity\": 1}]}" http://localhost:8092/api/v1/orders)
ORDER_ID_3=$(echo $ORDER_RESPONSE | jq -r '.data.id')

PAYLOAD="{\"event\": \"payment.success\",\"payload\": {\"payment\": {\"entity\": {\"order_id\": \"$ORDER_ID_3\",\"status\": \"captured\",\"amount\": 200.00}}}}"
SIG=$(python3 -c "import hmac, hashlib, base64; print(base64.b64encode(hmac.new(b'test_secret', b'$PAYLOAD', hashlib.sha256).digest()).decode())")
curl -s -X POST -H "Content-Type: application/json" -H "X-Vyapar-Signature: $SIG" -d "$PAYLOAD" http://localhost:8092/api/v1/webhooks/vyapar > /dev/null

sleep 2 # wait for paid
curl -s -X POST http://localhost:8092/api/v1/restaurants/$REST_ID/fulfillment/orders/$ORDER_ID_3/accept > /dev/null

echo "Waiting for driver to be dispatched..."
for i in {1..10}; do
    DISPATCHED_DRIVER_ID_3=$(docker exec -i food_delivery_db psql -U postgres -d food_delivery -t -c "SELECT delivery_executive_id FROM orders WHERE id = '$ORDER_ID_3';" | xargs)
    if [ "$DISPATCHED_DRIVER_ID_3" != "" ] && [ "$DISPATCHED_DRIVER_ID_3" != "null" ]; then
        break
    fi
    sleep 1
done

if [ "$DISPATCHED_DRIVER_ID_3" == "" ] || [ "$DISPATCHED_DRIVER_ID_3" == "null" ]; then
    echo "FAIL: Driver was not dispatched."
    exit 1
fi

echo "Simulating Driver Timeout..."
curl -s -X POST http://localhost:8092/api/delivery/drivers/$DISPATCHED_DRIVER_ID_3/orders/$ORDER_ID_3/timeout > /dev/null

echo "Timeout successfully simulated."

echo "--- SUCCESS: Test 9 Completed ---"
exit 0
