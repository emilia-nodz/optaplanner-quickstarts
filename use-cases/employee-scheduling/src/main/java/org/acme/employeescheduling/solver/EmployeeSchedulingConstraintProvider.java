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
                                noOverlappingShifts(constraintFactory),
                                weeklyHoursTarget(constraintFactory),
                                distributeShiftsEvenly(constraintFactory),
                                consecutiveShiftsPreference(constraintFactory),
                                availableDayForEmployee(constraintFactory),
                                unavailableEmployee(constraintFactory),
                                desiredDayForEmployee(constraintFactory),
                                undesiredDayForEmployee(constraintFactory),
                };
        }

        Constraint requiredSkill(ConstraintFactory constraintFactory) {
                return constraintFactory.forEach(Shift.class)
                                .filter(shift -> shift.getEmployee() != null) 
                                .filter(shift -> !shift.getEmployee().getSkillSet().contains(shift.getRequiredSkill()))
                                .penalize(HardSoftScore.ONE_HARD)
                                .asConstraint("Missing required skill");
        }

        Constraint noOverlappingShifts(ConstraintFactory constraintFactory) {
                return constraintFactory.forEachUniquePair(Shift.class,
                                Joiners.equal(Shift::getEmployee),
                                Joiners.overlapping(Shift::getStart, Shift::getEnd))
                                .filter((shift1, shift2) -> shift1.getEmployee() != null) 
                                .penalize(HardSoftScore.ONE_HARD,
                                                EmployeeSchedulingConstraintProvider::getMinuteOverlap)
                                .asConstraint("Overlapping shift");
        }

        
        Constraint weeklyHoursTarget(ConstraintFactory constraintFactory) {
                return constraintFactory.forEach(Shift.class)
                                .filter(shift -> shift.getEmployee() != null) 
                                .groupBy(shift -> shift.getEmployee(),
                                        shift -> shift.getStart().toLocalDate()
                                                .with(java.time.DayOfWeek.MONDAY), 
                                                ConstraintCollectors.sum(
                                                        EmployeeSchedulingConstraintProvider::getShiftDurationInMinutes))
                                .filter((employee, weekStart, totalMinutes) -> totalMinutes != 2400) 
                                .penalize(HardSoftScore.ONE_SOFT,
                                                (employee, weekStart, totalMinutes) -> Math.abs(totalMinutes - 2400)
                                                                / 100) // Reduced weight
                                .asConstraint("Weekly hours should be 40h");
        }

        Constraint distributeShiftsEvenly(ConstraintFactory constraintFactory) {
                return constraintFactory.forEach(Shift.class)
                                .filter(shift -> shift.getEmployee() != null) 
                                .groupBy(Shift::getEmployee, ConstraintCollectors.count())
                                .penalize(HardSoftScore.ofSoft(10), 
                                        (employee, shiftCount) -> shiftCount.intValue() * shiftCount.intValue() * 3)
                                .asConstraint("Distribute shifts evenly among employees");
        }

        Constraint consecutiveShiftsPreference(ConstraintFactory constraintFactory) {
                return constraintFactory.forEachUniquePair(Shift.class,
                                Joiners.equal(Shift::getEmployee),
                                Joiners.lessThan(Shift::getEnd, Shift::getStart))
                                .filter((shift1, shift2) -> shift1.getEmployee() != null)
                                .filter((shift1, shift2) -> {
                                        long minutesBetween = Duration.between(shift1.getEnd(), shift2.getStart()).toMinutes();
                                        return minutesBetween >= 0 && minutesBetween <= 10;
                                })
                                .reward(HardSoftScore.ofSoft(5)) 
                                .asConstraint("Consecutive shifts preference");
        }

        Constraint availableDayForEmployee(ConstraintFactory constraintFactory) {
                return constraintFactory.forEach(Shift.class)
                                .filter(shift -> shift.getEmployee() != null)
                                .join(Availability.class,
                                                Joiners.equal((Shift shift) -> shift.getStart().toLocalDate(), Availability::getDate),
                                                Joiners.equal(Shift::getEmployee, Availability::getEmployee))
                                .filter((shift, availability) -> availability
                                                .getAvailabilityType() == AvailabilityType.AVAILABLE)
                                .reward(HardSoftScore.ONE_SOFT,
                                                (shift, availability) -> getShiftDurationInMinutes(shift))
                                .asConstraint("Available day for employee");
        }

        Constraint unavailableEmployee(ConstraintFactory constraintFactory) {
                return constraintFactory.forEach(Shift.class)
                                .filter(shift -> shift.getEmployee() != null)
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
                                .filter(shift -> shift.getEmployee() != null)
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
                                .filter(shift -> shift.getEmployee() != null)
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
