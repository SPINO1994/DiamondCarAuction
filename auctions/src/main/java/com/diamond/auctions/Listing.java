package com.diamond.auctions;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

record Listing(
    @NotBlank @Size(max=50) String make,
    @NotBlank @Size(max=50) String model,
    @NotBlank @Size(max=60) String trim,
    @Min(1886) @Max(2100) int year,
    @Min(0) @Max(9999999) int mileage,
    @Pattern(regexp="mi|km") @NotNull String mileageUnit,
    @NotBlank @Size(max=40) String transmission,
    @NotBlank @Size(max=40) String fuel,
    @NotBlank @Size(max=40) String bodyStyle,
    @NotBlank @Size(max=40) String exterior,
    @NotBlank @Size(max=40) String interior,
    @NotBlank @Size(max=100) String location,
    @NotBlank @Size(max=5000) String description,
    @NotBlank @Size(max=3000) String condition,
    @NotBlank @Size(max=3000) String serviceHistory,
    @NotNull @Size(max=12) List<@NotBlank @Size(max=100) String> images
) {
    record Input(@NotNull @Valid Listing vehicle,
        @NotNull @DecimalMin("0.01") @Digits(integer=12,fraction=2) BigDecimal startingPrice,
        @NotNull @DecimalMin("0.01") @Digits(integer=12,fraction=2) BigDecimal increment,
        @DecimalMin("0.01") @Digits(integer=12,fraction=2) BigDecimal reserve,
        @NotNull OffsetDateTime startsAt, @NotNull OffsetDateTime endsAt) {}
}
