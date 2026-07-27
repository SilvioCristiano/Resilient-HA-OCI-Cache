check "regions_are_distinct" {
  assert {
    condition     = var.primary_region != var.standby_region
    error_message = "primary_region e standby_region devem ser diferentes."
  }
}

check "network_inputs_are_complete" {
  assert {
    condition = var.create_networks || (
      var.primary_existing_vcn_id != null &&
      var.primary_existing_subnet_id != null &&
      var.standby_existing_vcn_id != null &&
      var.standby_existing_subnet_id != null
    )
    error_message = "Quando create_networks=false, informe VCN e subnet existentes nas duas regiões."
  }
}

check "cache_user_has_authentication" {
  assert {
    condition     = !var.create_cache_user || nonsensitive(length(var.cache_user_password_hashes)) > 0
    error_message = "Quando create_cache_user=true, informe pelo menos um hash em cache_user_password_hashes."
  }
}

check "clusters_have_cache_users" {
  assert {
    condition = var.create_cache_user || (
      length(var.primary_existing_cache_user_ocids) > 0 &&
      length(var.standby_existing_cache_user_ocids) > 0
    )
    error_message = "Crie o usuário com create_cache_user=true ou informe usuários existentes para os dois clusters."
  }
}
