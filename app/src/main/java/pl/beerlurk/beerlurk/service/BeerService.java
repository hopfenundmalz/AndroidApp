package pl.beerlurk.beerlurk.service;

import android.location.Location;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import pl.beerlurk.beerlurk.dto.BeerLocation;
import pl.beerlurk.beerlurk.dto.BeerLocationsWrapper;
import pl.beerlurk.beerlurk.dto.DistancedBeerLocation;
import pl.beerlurk.beerlurk.dto.geocode.Geometry;
import pl.beerlurk.beerlurk.dto.geocode.ResultsWrapper;
import pl.beerlurk.beerlurk.dto.matrix.Distance;
import pl.beerlurk.beerlurk.dto.matrix.Element;
import pl.beerlurk.beerlurk.dto.matrix.MatrixData;
import pl.beerlurk.beerlurk.dto.matrix.Row;
import rx.Observable;
import rx.functions.Func1;
import rx.functions.Func2;

public final class BeerService {

    private final BeerApi beerApi;
    private final MatrixApi matrixApi;
    private final GeocodeApi geocodeApi;

    public BeerService(BeerApi api, MatrixApi matrixApi, GeocodeApi geocodeApi) {
        this.beerApi = api;
        this.matrixApi = matrixApi;
        this.geocodeApi = geocodeApi;
    }

    public Observable<List<DistancedBeerLocation>> call(String beerName, final Location myLocation) {
        return beerApi.call(beerName)
                .flatMapIterable(new Func1<BeerLocationsWrapper, Iterable<? extends BeerLocation>>() {
                    @Override
                    public Iterable<? extends BeerLocation> call(BeerLocationsWrapper beerLocationsWrapper) {
                        return beerLocationsWrapper.getBeerLocations();
                    }
                })
                .filter(new Func1<BeerLocation, Boolean>() {
                    @Override
                    public Boolean call(BeerLocation beerLocation) {
                        return true;
                    }
                })
                .toList()
                .flatMap(new Func1<List<BeerLocation>, Observable<List<DistancedBeerLocation>>>() {
                    @Override
                    public Observable<List<DistancedBeerLocation>> call(List<BeerLocation> beerLocations) {
                        String origin = myLocation.getLatitude() + "," + myLocation.getLongitude();
                        String destinations = concatenateAddresses(beerLocations);
                        return mergeWithDistanceMatrix(beerLocations, origin, destinations);
                    }
                })
                .flatMapIterable(new Func1<List<DistancedBeerLocation>, Iterable<DistancedBeerLocation>>() {
                    @Override
                    public Iterable<DistancedBeerLocation> call(List<DistancedBeerLocation> distancedBeerLocations) {
                        return distancedBeerLocations;
                    }
                })
                .filter(new Func1<DistancedBeerLocation, Boolean>() {
                    @Override
                    public Boolean call(DistancedBeerLocation distancedBeerLocation) {
                        return distancedBeerLocation.getDistance() > 0;
                    }
                })
                .toSortedList(getSortFunction())
                .flatMapIterable(new Func1<List<DistancedBeerLocation>, Iterable<DistancedBeerLocation>>() {
                    @Override
                    public Iterable<DistancedBeerLocation> call(List<DistancedBeerLocation> distancedBeerLocations) {
                        return distancedBeerLocations;
                    }
                })
                .take(10)
                .flatMap(new Func1<DistancedBeerLocation, Observable<DistancedBeerLocation>>() {
                    @Override
                    public Observable<DistancedBeerLocation> call(final DistancedBeerLocation distancedBeerLocation) {
                        return getAddressLocation(distancedBeerLocation);
                    }
                })
                .toSortedList(getSortFunction());
    }

    private Func2<DistancedBeerLocation, DistancedBeerLocation, Integer> getSortFunction() {
        return new Func2<DistancedBeerLocation, DistancedBeerLocation, Integer>() {
            @Override
            public Integer call(DistancedBeerLocation distancedBeerLocation, DistancedBeerLocation distancedBeerLocation2) {
                return compareByDistance(distancedBeerLocation, distancedBeerLocation2);
            }
        };
    }

    private String concatenateAddresses(List<BeerLocation> beerLocations) {
        List<String> locations = new ArrayList<>();
        for (BeerLocation l : beerLocations) {
            String addressWithName = addressFromLocation(l);
            locations.add(addressWithName);
        }
        return TextUtils.join("|", locations);
    }

    private Integer compareByDistance(DistancedBeerLocation distancedBeerLocation, DistancedBeerLocation distancedBeerLocation2) {
        int d1 = distancedBeerLocation.getDistance();
        int d2 = distancedBeerLocation2.getDistance();
        if (d1 == d2) {
            return 0;
        } else if (d1 < d2) {
            return -1;
        } else {
            return 1;
        }
    }

    private Observable<DistancedBeerLocation> getAddressLocation(final DistancedBeerLocation distancedBeerLocation) {
        return geocodeApi.call(addressFromLocation(distancedBeerLocation.getBeerLocation()))
                .map(new Func1<ResultsWrapper, DistancedBeerLocation>() {
                    @Override
                    public DistancedBeerLocation call(ResultsWrapper resultsWrapper) {
                        Geometry geometry = resultsWrapper.getResults().get(0).getGeometry();
                        double lat = geometry.getLocation().getLat();
                        double lng = geometry.getLocation().getLng();
                        Location location = new Location("beer");
                        location.setLatitude(lat);
                        location.setLongitude(lng);
                        distancedBeerLocation.setLocation(location);
                        return distancedBeerLocation;
                    }
                });
    }

    private Observable<List<DistancedBeerLocation>> mergeWithDistanceMatrix(List<BeerLocation> beerLocations, String origin, String destinations) {
        return matrixApi.call(origin, destinations)
                .flatMapIterable(new Func1<MatrixData, Iterable<Distance>>() {
                    @Override
                    public Iterable<Distance> call(MatrixData matrixData) {
                        return getDistancesFlattened(matrixData);
                    }
                })
                .zipWith(beerLocations, new Func2<Distance, BeerLocation, DistancedBeerLocation>() {
                    @Override
                    public DistancedBeerLocation call(Distance distance, BeerLocation beerLocation) {
                        return new DistancedBeerLocation(distance != null ? distance.getValue() : 0, distance != null ? distance.getText() : "", beerLocation);
                    }
                })
                .toList();
    }

    private Iterable<Distance> getDistancesFlattened(MatrixData matrixData) {
        List<Distance> distances = new ArrayList<>();
        for (Row row : matrixData.getRows()) {
            for (Element element : row.getElements()) {
                if (element.getDistance() != null) {
                    distances.add(element.getDistance());
                } else {
                    distances.add(null);
                }
            }
        }
        return distances;
    }

    private String addressFromLocation(BeerLocation l) {
        String addressWithName = l.getAddressWithName();
        int indexOfBracket = addressWithName.indexOf('(');
        if (indexOfBracket >= 0) {
            addressWithName = addressWithName.substring(0, indexOfBracket);
        }
        List<String> parts = Arrays.asList(addressWithName.split(","));
        if (parts.size() > 2) {
            parts = Arrays.asList(parts.get(0), parts.get(1));
        }
        addressWithName = TextUtils.join(",", parts);
        return addressWithName;
    }
}
