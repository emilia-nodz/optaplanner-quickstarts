package org.acme.employeescheduling.solver;

import java.time.Duration;
import java.time.LocalDateTime;

import org.acme.employeescheduling.domain.Availability;
import org.acme.employeescheduling.domain.AvailabilityType;
import org.acme.employeescheduling.domain.Shift;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.optaplanner.core.api.score.stream.Constraint;
import org.optaplanner.core.api.score.stream.ConstraintCollectors;
import org.optaplanner.core.api.score.stream.ConstraintFactory;
import org.optaplanner.core.api.score.stream.ConstraintProvider;
import org.optaplanner.core.api.score.stream.Joiners;

public class EmployeeSchedulingConstraintProvider implements ConstraintProvider {

        private static int getMinuteOverlap(Shift shift1, Shift shift2) {
                // The overlap of two timeslot occurs in the range common to both timeslots.
                // Both timeslots are active after the higher of their two start times,
                // and before the lower of their two end times.
                LocalDateTime shift1Start = shift1.getStart();
                LocalDateTime shift1End = shift1.getEnd();
                LocalDateTime shift2Start = shift2.getStart();
                LocalDateTime shift2End = shift2.getEnd();
                return (int) Duration.between((shift1Start.compareTo(shift2Start) > 0) ? shift1Start : shift2Start,
                                (shift1End.compareTo(shift2End) < 0) ? shift1End : shift2End).toMinutes();
        }

        private static int getShiftDurationInMinutes(Shift shift) {
                return (int) Duration.between(shift.getStart(), shift.getEnd()).toMinutes();
        }

        @Override
        public Constraint[] defineConstraints(ConstraintFactory constraintFactory) {
                return new Constraint[] {
                                // requiredSkill(constraintFactory), // Wyłączone - użytkownik wybierze typ
                                // masażu podczas rezerwacji
                                noOverlappingShifts(constraintFactory),
                                // atLeast10HoursBetweenTwoShifts(constraintFactory),
                                // oneShiftPerDay(constraintFactory),
                                weeklyHoursTarget(constraintFactory),
                                distributeShiftsEvenly(constraintFactory), // Równomierne rozłożenie shiftów
                                availableDayForEmployee(constraintFactory),
                                unavailableEmployee(constraintFactory),
                                desiredDayForEmployee(constraintFactory),
                                undesiredDayForEmployee(constraintFactory),
                };
        }

        Constraint requiredSkill(ConstraintFactory constraintFactory) {
                return constraintFactory.forEach(Shift.class)
                                .filter(shift -> shift.getEmployee() != null) // Only check assigned shifts
                                .filter(shift -> !shift.getEmployee().getSkillSet().contains(shift.getRequiredSkill()))
                                .penalize(HardSoftScore.ONE_HARD)
                                .asConstraint("Missing required skill");
        }

        // NAJWAŻNIEJSZE: Shifty nie mogą się nakładać dla tego samego pracownika
        Constraint noOverlappingShifts(ConstraintFactory constraintFactory) {
                return constraintFactory.forEachUniquePair(Shift.class,
                                Joiners.equal(Shift::getEmployee),
                                Joiners.overlapping(Shift::getStart, Shift::getEnd))
                                .filter((shift1, shift2) -> shift1.getEmployee() != null) // Only check assigned shifts
                                .penalize(HardSoftScore.ONE_HARD,
                                                EmployeeSchedulingConstraintProvider::getMinuteOverlap)
                                .asConstraint("Overlapping shift");
        }

        // Constraint atLeast10HoursBetweenTwoShifts(ConstraintFactory
        // constraintFactory) {
        // return constraintFactory.forEachUniquePair(Shift.class,
        // Joiners.equal(Shift::getEmployee),
        // Joiners.lessThanOrEqual(Shift::getEnd, Shift::getStart))
        // .filter((firstShift, secondShift) -> firstShift.getEmployee() != null) //
        // Only check
        // // assigned
        // // shifts
        // .filter((firstShift, secondShift) -> Duration
        // .between(firstShift.getEnd(), secondShift.getStart()).toHours() < 10)
        // .penalize(HardSoftScore.ONE_HARD,
        // (firstShift, secondShift) -> {
        // int breakLength = (int) Duration
        // .between(firstShift.getEnd(),
        // secondShift.getStart())
        // .toMinutes();
        // return (10 * 60) - breakLength;
        // })
        // .asConstraint("At least 10 hours between 2 shifts");
        // }

        // Constraint oneShiftPerDay(ConstraintFactory constraintFactory) {
        // return constraintFactory.forEachUniquePair(Shift.class,
        // Joiners.equal(Shift::getEmployee),
        // Joiners.equal(shift -> shift.getStart().toLocalDate()))
        // .filter((shift1, shift2) -> shift1.getEmployee() != null) // Only check
        // assigned shifts
        // .penalize(HardSoftScore.ONE_HARD)
        // .asConstraint("Max one shift per day");
        // }

        // Constraint na 40h tygodniowo (czyli około 44 shiftów po 55 minut = 2420 minut
        // = ~40.3h)
        Constraint weeklyHoursTarget(ConstraintFactory constraintFactory) {
                return constraintFactory.forEach(Shift.class)
                                .filter(shift -> shift.getEmployee() != null) // Only count assigned shifts
                                .groupBy(shift -> shift.getEmployee(),
                                                shift -> shift.getStart().toLocalDate()
                                                                .with(java.time.DayOfWeek.MONDAY), // Start of week
                                                ConstraintCollectors.sum(
                                                                EmployeeSchedulingConstraintProvider::getShiftDurationInMinutes))
                                .filter((employee, weekStart, totalMinutes) -> totalMinutes != 2400) // Target: 40 hours
                                                                                                     // = 2400 minutes
                                .penalize(HardSoftScore.ONE_SOFT,
                                                (employee, weekStart, totalMinutes) -> Math.abs(totalMinutes - 2400)
                                                                / 100) // Reduced weight
                                .asConstraint("Weekly hours should be 40h");
        }

        // Równomierne rozłożenie shiftów między pracowników - WYSOKA WAGA
        Constraint distributeShiftsEvenly(ConstraintFactory constraintFactory) {
                return constraintFactory.forEach(Shift.class)
                                .filter(shift -> shift.getEmployee() != null) // Only count assigned shifts
                                .groupBy(Shift::getEmployee, ConstraintCollectors.count())
                                .penalize(HardSoftScore.ofSoft(10), // Higher weight to overcome other soft constraints
                                                (employee, shiftCount) -> shiftCount.intValue() * shiftCount.intValue()
                                                                * 3) // Triple penalty
                                .asConstraint("Distribute shifts evenly among employees");
        }

        Constraint availableDayForEmployee(ConstraintFactory constraintFactory) {
                return constraintFactory.forEach(Shift.class)
                                .filter(shift -> shift.getEmployee() != null) // Only check assigned shifts
                                .join(Availability.class,
                                                Joiners.equal((Shift shift) -> shift.getStart().toLocalDate(),
                                                                Availability::getDate),
                                                Joiners.equal(Shift::getEmployee, Availability::getEmployee))
                                .filter((shift, availability) -> availability
                                                .getAvailabilityType() == AvailabilityType.AVAILABLE)
                                .reward(HardSoftScore.ONE_SOFT,
                                                (shift, availability) -> getShiftDurationInMinutes(shift))
                                .asConstraint("Available day for employee");
        }

        Constraint unavailableEmployee(ConstraintFactory constraintFactory) {
                return constraintFactory.forEach(Shift.class)
                                .filter(shift -> shift.getEmployee() != null) // Only check assigned shifts
                                .join(Availability.class,
                                                Joiners.equal((Shift shift) -> shift.getStart().toLocalDate(),
                                                                Availability::getDate),
                                                Joiners.equal(Shift::getEmployee, Availability::getEmployee))
                                .filter((shift, availability) -> availability
                                                .getAvailabilityType() == AvailabilityType.UNAVAILABLE)
                                .penalize(HardSoftScore.ONE_HARD,
                                                (shift, availability) -> getShiftDurationInMinutes(shift))
                                .asConstraint("Unavailable employee");
        }

        Constraint desiredDayForEmployee(ConstraintFactory constraintFactory) {
                return constraintFactory.forEach(Shift.class)
                                .filter(shift -> shift.getEmployee() != null) // Only check assigned shifts
                                .join(Availability.class,
                                                Joiners.equal((Shift shift) -> shift.getStart().toLocalDate(),
                                                                Availability::getDate),
                                                Joiners.equal(Shift::getEmployee, Availability::getEmployee))
                                .filter((shift, availability) -> availability
                                                .getAvailabilityType() == AvailabilityType.DESIRED)
                                .reward(HardSoftScore.ONE_SOFT,
                                                (shift, availability) -> getShiftDurationInMinutes(shift))
                                .asConstraint("Desired day for employee");
        }

        Constraint undesiredDayForEmployee(ConstraintFactory constraintFactory) {
                return constraintFactory.forEach(Shift.class)
                                .filter(shift -> shift.getEmployee() != null) // Only check assigned shifts
                                .join(Availability.class,
                                                Joiners.equal((Shift shift) -> shift.getStart().toLocalDate(),
                                                                Availability::getDate),
                                                Joiners.equal(Shift::getEmployee, Availability::getEmployee))
                                .filter((shift, availability) -> availability
                                                .getAvailabilityType() == AvailabilityType.UNDESIRED)
                                .penalize(HardSoftScore.ONE_SOFT,
                                                (shift, availability) -> getShiftDurationInMinutes(shift))
                                .asConstraint("Undesired day for employee");
        }

}
