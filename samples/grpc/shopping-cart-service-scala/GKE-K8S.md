# Steps for running this sample on GKE

## Replicated Event Sourcing over gRPC

Setup: Two k8s clusters with Akka clusters in them, each with their own postgres db 

### Create Cloud SQL instances (in the same regions you will create gke clusters)

https://console.cloud.google.com/sql/instances?project=akka-team

Create the schema/tables: 

    gcloud sql connect [your-db-instance-id] --user=postgres --quiet
 
Schema is found in ddl-scripts/create_tables.sql

Under "connections" in the web console, open up for connections from anywhere through "ADD NETWORK" and defining a network `0.0.0.0/0` (obv bad for security reasons but makes connecting from k8s nodes _so_ much easier than the prod-worthy options)

### Update config

If running more than 2 dcs:

Update `src/main/resources/replication.conf` to have the right number of datacenters,
with env vars to configure the self-dc and public ips of each.

### Publish docker image to gcr.io

```
     gcloud auth print-access-token | docker login -u oauth2accesstoken --password-stdin https://gcr.io
     sbt Docker/publish -Ddocker.registry=gcr.io -Ddocker.username=akka-team
```

### Create k8s clusters

In the same region as the db.

Or use an existing one?

For each cluster

#### Set up kubectl access

    gcloud container clusters get-credentials res-grpc-test-dca-cluster --region us-east1 --project akka-team

This will add an entry to your kube config for the cluster (and make it the "current-context"?)

I figured out the full context name by greping ~/.kube/config maybe there is a better way.

#### Create the akka-grpc-projection namespace

    kubectl --context [context] create namespace akka-grpc-projection

#### Set DB Access secrets

Using the db password chosen when creating the db:

    kubectl --context [context] --namespace akka-grpc-projection create secret generic benchmark-db-secret --from-literal=username=postgres --from-literal=password='[db-password]'

#### RBAC

To allow akka management to read the pods from k8s API,

Possibly but not sure - I don't have permissions enough to give the service account any perms: First we need a service account, create a service account named `akka-grpc-projection` at: https://console.cloud.google.com/iam-admin/serviceaccounts?project=akka-team

Then deploy the rbac


    kubectl --context [context] --namespace akka-grpc-projection create -f k8s/rbac.yaml

#### Create public ingress/load balancers for the service

    kubectl --context [context] --namespace akka-grpc-projection create -f k8s/external-service.yaml

Public endpoint IP can now be seen through https://console.cloud.google.com/kubernetes/discovery?project=akka-team

FIXME should it be HTTPS instead since replicating across public internet?

#### Deploy the service

Update/copy k8s/deployment-gke-replicated-dca.yaml with:

 * the tag you published to gcr in the end of `image: gcr.io/akka-team/shopping-cart-service:[tag]`
 * Each DC public IP from the previous step in `DCA_HOST`, `DCB_HOST` etc. 
 * Database public IP from the local db in `DB_HOST`
 * `SELF_REPLICA_ID` with the logical Akka replica name of the cluster itself.

 
    kubectl --context [context] --namespace akka-grpc-projection create -f k8s/deployment-gke-replicated-dca.yaml

See the cluster form with kubectl or k9s, once it has formed, check that the cluster and db connection works by hitting
the API:

    grpcurl -d '{"cartId":"cart1", "itemId":"socks", "quantity":3}' -plaintext [public-ip]:80 shoppingcart.ShoppingCartService.AddItem
    grpcurl -d '{"cartId":"cart1"}' -plaintext [public-ip]:80 shoppingcart.ShoppingCartService.GetCart

### Running the simulation

The simulation is not autostarted but can be triggered with the gRPC call:




### Once done

Remember to delete Cloud SQL databases and the K8 cluster and not leave anything running.