
output "status" {
    value = ibm_pi_instance.pvminstance.status
}

output "min_proc" {
    value = ibm_pi_instance.pvminstance.min_processors
}

output "health_status" {
    value = ibm_pi_instance.pvminstance.health_status
}

output "addresses" {
    value = ibm_pi_instance.pvminstance.pi_network
}

output "progress" {
    value = ibm_pi_instance.pvminstance.pi_progress
}
