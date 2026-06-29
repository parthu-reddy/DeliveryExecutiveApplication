#!/bin/bash
set -e

echo "======================================"
echo "   FOOD DELIVERY E2E TEST SUITE"
echo "======================================"
echo ""

chmod +x tests/*.sh

echo "Clearing Redis..."
docker exec -i food_delivery_redis redis-cli FLUSHALL > /dev/null


echo "Running Test 1: Happy Path Full Cycle..."
./tests/test_01_happy_path_full.sh
echo "Test 1 PASSED."
echo ""

echo "Running Test 2: Restaurant Rejection..."
./tests/test_02_restaurant_rejection.sh
echo "Test 2 PASSED."
echo ""

echo "Running Test 3: No Driver Available..."
./tests/test_03_no_driver_available.sh
echo "Test 3 PASSED."
echo ""

echo "Running Test 4: Validation Failures..."
./tests/test_04_validation_failures.sh
echo "Test 4 PASSED."
echo ""

echo "Running Test 5: Driver Rejection..."
./tests/test_05_driver_rejection.sh
echo "Test 5 PASSED."
echo ""

echo "Running Test 6: Invalid Status Transitions..."
./tests/test_06_invalid_status_transitions.sh
echo "Test 6 PASSED."
echo ""

echo "Running Test 7: Payment Timeout Sim..."
./tests/test_07_payment_timeout_sim.sh
echo "Test 7 PASSED."
echo ""

echo "Running Test 8: Exhaustive Step Failures..."
./tests/test_08_exhaustive_step_failures.sh
echo "Test 8 PASSED."
echo ""

echo "Running Test 9: New Edge Cases..."
./tests/test_09_new_edge_cases.sh
echo "Test 9 PASSED."
echo ""

echo "======================================"
echo "   ALL TESTS COMPLETED SUCCESSFULLY"
echo "======================================"
exit 0
