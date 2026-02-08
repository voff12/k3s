#!/bin/bash
echo "Searching for K3s configuration..."

# Check standard locations
LOCATIONS=(
    "/etc/rancher/k3s/k3s.yaml"
    "$HOME/.kube/config"
    "$HOME/.kube/k3s.yaml"
)

FOUND=0

for loc in "${LOCATIONS[@]}"; do
    if [ -f "$loc" ]; then
        echo "Found config candidate at: $loc"
        # Check if it looks like a K3s config
        if grep -q "k3s" "$loc" || grep -q "default" "$loc"; then
             echo "  -> Content suggests this might be valid."
             FOUND=1
        fi
    fi
done

if [ $FOUND -eq 0 ]; then
    echo "Could not find standard K3s config files."
    echo "Please check if K3s is running and where it wrote the kubeconfig."
    echo "You might need to use 'sudo' to read /etc/rancher/k3s/k3s.yaml"
else
    echo ""
    echo "To use a config file, update src/main/resources/application.properties:"
    echo "k8s.kubeconfig=/path/to/found/config.yaml"
fi
